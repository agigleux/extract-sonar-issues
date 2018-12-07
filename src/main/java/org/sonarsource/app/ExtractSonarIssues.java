package org.sonarsource.app;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.google.gson.Gson;
import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.Realm;
import com.ning.http.client.Realm.AuthScheme;
import com.ning.http.client.Response;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonarsource.app.json.Issue;
import org.sonarsource.app.json.IssuesList;

public class ExtractSonarIssues {

  private static final Logger LOGGER = LoggerFactory.getLogger(ExtractSonarIssues.class);

  private static final String API_ISSUES_SEARCH = "api/issues/search";
  private static final int HTTP_STATUS_OK = 200;
  private static final int MAX_ITEMS_RETURNED_BY_PAGE_BY_SQ_WS = 500;
  private static final double MAX_ITEMS_RETURNED_BY_PAGE_BY_SQ_WS_AS_LONG = 500.0;
  private static final double MAX_ITEMS_RETURNED_BY_SQ_WS_AS_LONG = 10000;

  @Parameter(names = "-sq.url", description = "SonarQube or SonarCloud URL", required = true)
  private String url;

  @Parameter(names = "-user.token", description = "SonarQube or SonarCloud User Token", required = true)
  private String token;

  @Parameter(names = "-project.key", description = "SonarQube or SonarCloud Project Key", required = true)
  private String projectKey;

  private Map<String, List<Issue>> extractedIssues = new TreeMap<>();

  private Realm getAuthentificationRealm() {
    return new Realm.RealmBuilder()
      .setPrincipal(token)
      .setPassword("")
      .setUsePreemptiveAuth(true)
      .setScheme(AuthScheme.BASIC)
      .build();
  }

  public static void main(String[] args) throws FileNotFoundException {
    LOGGER.info("Extract Sonar Issues: Started");
    ExtractSonarIssues app = new ExtractSonarIssues();

    new JCommander(app, args);
    app.process();

    LOGGER.info("Extract Sonar Issues: Done");
    System.exit(0);
  }

  public void process() throws FileNotFoundException {
    AsyncHttpClient httpClient = null;

    try {
      httpClient = new AsyncHttpClient();
      Realm realm = getAuthentificationRealm();

      IssuesList issues = gatherSonarQubeIssuesOnProject(httpClient, realm);
      populateIssuesByFile(issues);
      generate();

    } catch (InterruptedException | ExecutionException | IOException e) {
      LOGGER.error("Aborted", e);
    } finally {
      if (httpClient != null) {
        httpClient.close();
      }
    }

  }

  private void generate() throws FileNotFoundException, UnsupportedEncodingException {
    PrintWriter writer = new PrintWriter("extract.txt", "UTF-8");

    try {
      for (String filePath : extractedIssues.keySet()) {
        List<Issue> issuesOfFile = extractedIssues.get(filePath);
        for (Issue issue : issuesOfFile) {
          writer.println("'" + filePath + "'," + issue.getType() + "," + issue.getRule() + "," + issue.getLine());
        }
      }
    } finally {
      writer.close();
    }
  }

  private void populateIssuesByFile(IssuesList il) {
    if (il != null) {
      List<Issue> issues = il.getIssues();
      for (Issue issue : issues) {
        List<Issue> issuesOfFile = extractedIssues.get(issue.getComponent());
        if (issuesOfFile == null) {
          issuesOfFile = new ArrayList<>();
          extractedIssues.put(issue.getComponent(), issuesOfFile);
        }
        issuesOfFile.add(issue);
      }
    }
  }

  private IssuesList gatherSonarQubeIssuesOnProject(AsyncHttpClient httpClient, Realm realm) throws InterruptedException, ExecutionException, IOException {
    LOGGER.info("Gather SonarQube Issues On Project: Started");

    Future<com.ning.http.client.Response> f = httpClient.prepareGet(url + API_ISSUES_SEARCH)
      .setRealm(realm)
      .addQueryParam("componentKeys", projectKey)
      .addQueryParam("statuses", "OPEN")
      .addQueryParam("types", "BUG,VULNERABILITY,CODE_SMELL,SECURITY_HOTSPOT")
      .addQueryParam("p", "1")
      // this is the hard coded limit value in APIs, so we can't get
      // everything in bulk, we need to loop by block of 500
      .addQueryParam("ps", Integer.toString(MAX_ITEMS_RETURNED_BY_PAGE_BY_SQ_WS))
      .execute();

    Response r = f.get();
    String jsonData = r.getResponseBody();
    checkReturnCode(r);
    LOGGER.debug(jsonData);

    Gson gson = new Gson();
    IssuesList allIssues = gson.fromJson(jsonData, IssuesList.class);

    int totalItems = allIssues.getTotal();
    LOGGER.info("Total Issues : {}", totalItems);

    if (totalItems > MAX_ITEMS_RETURNED_BY_SQ_WS_AS_LONG) {
      LOGGER.error("SonarQube can only return the first {} results using Web API", MAX_ITEMS_RETURNED_BY_SQ_WS_AS_LONG);
      System.exit(-1);
    }

    if (totalItems > MAX_ITEMS_RETURNED_BY_PAGE_BY_SQ_WS) {

      int numberPages = (int) Math.ceil(totalItems / MAX_ITEMS_RETURNED_BY_PAGE_BY_SQ_WS_AS_LONG);
      LOGGER.info("Total Pages : {}", numberPages);
      for (int i = 2; i <= numberPages; i++) {
        LOGGER.info("Searching for Issues on Page {}", i);
        f = httpClient.prepareGet(url + API_ISSUES_SEARCH)
          .setRealm(realm)
          .addQueryParam("componentKeys", projectKey)
          .addQueryParam("statuses", "OPEN")
          .addQueryParam("types", "BUG,VULNERABILITY,CODE_SMELL,SECURITY_HOTSPOT")
          .addQueryParam("p", Integer.toString(i))
          .addQueryParam("ps", Integer.toString(MAX_ITEMS_RETURNED_BY_PAGE_BY_SQ_WS)) // this is the hard coded limit value in APIs, so we
                                                                                      // can't get
          // everything in bulk, we need to loop by block of 500
          .execute();

        r = f.get();
        jsonData = r.getResponseBody();
        checkReturnCode(r);
        LOGGER.debug(jsonData);

        gson = new Gson();
        IssuesList issuesPage = gson.fromJson(jsonData, IssuesList.class);
        allIssues.getIssues().addAll(issuesPage.getIssues());
        LOGGER.info("Total Issues: {}", allIssues.getIssues().size());
      }
    }

    LOGGER.info("Total Issues: {}", allIssues.getIssues().size());
    LOGGER.info("Gather SonarQube Issues On Project: Done");
    return allIssues;
  }

  private static void checkReturnCode(Response r) {
    if (r.getStatusCode() != HTTP_STATUS_OK) {
      LOGGER.warn("Status Code: {}", r.getStatusCode());
      System.exit(-1);
    }
  }
}
