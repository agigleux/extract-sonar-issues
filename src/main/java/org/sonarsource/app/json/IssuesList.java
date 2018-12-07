package org.sonarsource.app.json;

import java.util.ArrayList;
import java.util.List;

public class IssuesList {

  private int total;
  private List<Issue> issues;

  public IssuesList() {
    total = 0;
    issues = new ArrayList<>();
  }

  public int getTotal() {
    return total;
  }

  public void setTotal(int total) {
    this.total = total;
  }

  public List<Issue> getIssues() {
    return issues;
  }

  public void setIssues(List<Issue> issues) {
    this.issues = issues;
  }

}
