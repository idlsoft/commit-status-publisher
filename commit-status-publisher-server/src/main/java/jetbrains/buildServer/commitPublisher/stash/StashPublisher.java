/*
 * Copyright 2000-2022 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package jetbrains.buildServer.commitPublisher.stash;

import com.google.gson.*;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jetbrains.buildServer.commitPublisher.*;
import jetbrains.buildServer.commitPublisher.stash.data.DeprecatedJsonStashBuildStatuses;
import jetbrains.buildServer.commitPublisher.stash.data.JsonStashBuildStatus;
import jetbrains.buildServer.serverSide.*;
import jetbrains.buildServer.serverSide.impl.LogUtil;
import jetbrains.buildServer.users.User;
import jetbrains.buildServer.util.StringUtil;
import jetbrains.buildServer.util.VersionComparatorUtil;
import jetbrains.buildServer.util.http.HttpMethod;
import jetbrains.buildServer.vcs.VcsRootInstance;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import static jetbrains.buildServer.commitPublisher.LoggerUtil.LOG;

class StashPublisher extends HttpBasedCommitStatusPublisher {
  public static final String PROP_PUBLISH_QUEUED_BUILD_STATUS = "teamcity.stashCommitStatusPublisher.publishQueuedBuildStatus";
  private static final Pattern PULL_REQUEST_BRANCH_PATTERN = Pattern.compile("^refs\\/pull\\-requests\\/(\\d+)\\/from");
  private static final String SERVER_VERSION_BUILD_SERVER_HWM = "7.4";
  private static final String SERVER_VERSION_EXTENDED_SERVER_LWM = "7.14.0";

  private final Gson myGson = new Gson();
  private BitbucketEndpoint myBitbucketEndpoint = null;

  StashPublisher(@NotNull CommitStatusPublisherSettings settings,
                 @NotNull SBuildType buildType, @NotNull String buildFeatureId,
                 @NotNull WebLinks links, @NotNull Map<String, String> params,
                 @NotNull CommitStatusPublisherProblems problems) {
    super(settings, buildType, buildFeatureId, params, problems, links);
  }

  @NotNull
  public String toString() {
    return "stash";
  }

  @NotNull
  @Override
  public String getId() {
    return Constants.STASH_PUBLISHER_ID;
  }

  @Override
  public boolean buildQueued(@NotNull BuildPromotion buildPromotion, @NotNull BuildRevision revision, @NotNull AdditionalTaskInfo additionalTaskInfo) {
    vote(buildPromotion, revision, StashBuildStatus.INPROGRESS, additionalTaskInfo.getComment());
    return true;
  }

  @Override
  public boolean buildRemovedFromQueue(@NotNull BuildPromotion buildPromotion, @NotNull BuildRevision revision, @NotNull AdditionalTaskInfo additionalTaskInfo) {
    StashBuildStatus targetStatus = additionalTaskInfo.isPromotionReplaced() ? StashBuildStatus.INPROGRESS : StashBuildStatus.FAILED;
    vote(buildPromotion, revision, targetStatus, additionalTaskInfo.getComment());
    return true;
  }

  @Override
  public boolean buildStarted(@NotNull SBuild build, @NotNull BuildRevision revision) {
    vote(build, revision, StashBuildStatus.INPROGRESS, DefaultStatusMessages.BUILD_STARTED);
    return true;
  }

  @Override
  public boolean buildFinished(@NotNull SBuild build, @NotNull BuildRevision revision) {
    StashBuildStatus status = build.getBuildStatus().isSuccessful() ? StashBuildStatus.SUCCESSFUL : StashBuildStatus.FAILED;
    String description = build.getStatusDescriptor().getText();
    vote(build, revision, status, description);
    return true;
  }

  @Override
  public boolean buildCommented(@NotNull SBuild build, @NotNull BuildRevision revision, @Nullable User user, @Nullable String comment, boolean buildInProgress) {
    StashBuildStatus status;
    if (buildInProgress) {
      status = build.getBuildStatus().isSuccessful() ? StashBuildStatus.INPROGRESS : StashBuildStatus.FAILED;
    } else {
      status = build.getBuildStatus().isSuccessful() ? StashBuildStatus.SUCCESSFUL : StashBuildStatus.FAILED;
    }
    String description = build.getStatusDescriptor().getText();
    if (user != null && comment != null) {
      description += " with a comment by " + user.getExtendedName() + ": \"" + comment + "\"";
    }
    vote(build, revision, status, description);
    return true;
  }

  @Override
  public boolean buildMarkedAsSuccessful(@NotNull SBuild build, @NotNull BuildRevision revision, boolean buildInProgress) {
    vote(build, revision, buildInProgress ? StashBuildStatus.INPROGRESS : StashBuildStatus.SUCCESSFUL, DefaultStatusMessages.BUILD_MARKED_SUCCESSFULL);
    return true;
  }

  @Override
  public boolean buildInterrupted(@NotNull SBuild build, @NotNull BuildRevision revision) {
    vote(build, revision, StashBuildStatus.FAILED, build.getStatusDescriptor().getText());
    return true;
  }

  @Override
  public boolean buildFailureDetected(@NotNull SBuild build, @NotNull BuildRevision revision) {
    vote(build, revision, StashBuildStatus.FAILED, build.getStatusDescriptor().getText());
    return true;
  }

  @Override
  public RevisionStatus getRevisionStatusForRemovedBuild(@NotNull SQueuedBuild removedBuild, @NotNull BuildRevision revision) {
    BuildPromotion buildPromotion = removedBuild.getBuildPromotion();
    StatusRequestData data = new SBuildPromotionRequestData(buildPromotion, revision);
    JsonStashBuildStatus buildStatus = getEndpoint().getCommitBuildStatus(data, LogUtil.describe(buildPromotion));
    return getRevisionStatusForRemovedBuild(removedBuild, buildStatus);
  }

  RevisionStatus getRevisionStatusForRemovedBuild(@NotNull SQueuedBuild removedBuild, @Nullable JsonStashBuildStatus buildStatus) {
    if (buildStatus == null) {
      return null;
    }
    Event event = getTriggeredEvent(buildStatus);
    boolean isSameBuild = StringUtil.areEqual(myLinks.getQueuedBuildUrl(removedBuild), buildStatus.url);
    return new RevisionStatus(event, buildStatus.description, isSameBuild);
  }

  @Override
  public RevisionStatus getRevisionStatus(@NotNull BuildPromotion buildPromotion, @NotNull BuildRevision revision) throws PublisherException {
    StatusRequestData data = new SBuildPromotionRequestData(buildPromotion, revision);
    JsonStashBuildStatus buildStatus = getEndpoint().getCommitBuildStatus(data, LogUtil.describe(buildPromotion));
    return getRevisionStatus(buildPromotion, buildStatus);
  }

  @Nullable
  RevisionStatus getRevisionStatus(@NotNull BuildPromotion buildPromotion, @Nullable JsonStashBuildStatus buildStatus) {
    if (buildStatus == null) {
      return null;
    }
    Event event = getTriggeredEvent(buildStatus);
    boolean isSameBuild = StringUtil.areEqual(getViewUrl(buildPromotion), buildStatus.url);
    return new RevisionStatus(event, buildStatus.description, isSameBuild);
  }

  private Event getTriggeredEvent(JsonStashBuildStatus buildStatus) {
    if (buildStatus.state == null) {
      LOG.warn("No Bitbucket build status is provided. Related event can not be defined");
      return null;
    }
    StashBuildStatus status = StashBuildStatus.getByName(buildStatus.state);
    if (status == null) {
      LOG.warn(String.format("Unknown Bitbucket build status: \"%s\". Related event can not be defined", buildStatus.state));
      return null;
    }
    switch (status) {
      case INPROGRESS:
        if (buildStatus.description == null) return null;
        return buildStatus.description.contains(DefaultStatusMessages.BUILD_QUEUED) ? Event.QUEUED :
               buildStatus.description.contains(DefaultStatusMessages.BUILD_REMOVED_FROM_QUEUE) ? Event.REMOVED_FROM_QUEUE : null;

      case FAILED:
      case SUCCESSFUL:
        return null;  // these statuses do not affect on further behaviour
      default:
        LOG.warn("No event is assosiated with Bitbucket build status \"" + buildStatus.state + "\". Related event can not be defined");
    }
    return null;
  }

  // There are two ways in Bitbucket Server to report the build status: the older Build API and a new endpoint in the Core API
  // We will be using the former if the Bitbucket Server version is below 7.4 or not retrieved by any reason
  private boolean useBuildAPI() {
    if (myBuildType instanceof BuildTypeEx && ((BuildTypeEx)myBuildType).getBooleanInternalParameter("commitStatusPublisher.enforceDeprecatedAPI"))
      return true;
    // NOTE: compare(null, "7.4") < 0
    return VersionComparatorUtil.compare(getSettings().getServerVersion(getBaseUrl()), "7.4") < 0;
  }

  private void vote(@NotNull SBuild build,
                    @NotNull BuildRevision revision,
                    @NotNull StashBuildStatus status,
                    @NotNull String comment) {
    String vcsBranch = getVcsBranch(revision, LogUtil.describe(build));
    SBuildData data = new SBuildData(build, revision, status, comment, vcsBranch);
    getEndpoint().publishBuildStatus(data, LogUtil.describe(build));
  }

  private void vote(@NotNull BuildPromotion buildPromotion,
                    @NotNull BuildRevision revision,
                    @NotNull StashBuildStatus status,
                    @NotNull String comment) {
    String vcsBranch = getVcsBranch(revision, LogUtil.describe(buildPromotion));
    SBuildPromotionData data = new SBuildPromotionData(buildPromotion, revision, status, comment, vcsBranch);
    getEndpoint().publishBuildStatus(data, LogUtil.describe(buildPromotion));
  }

  @Nullable
  private String getVcsBranch(@NotNull BuildRevision revision, @NotNull String buildDescription) {
    String revisionVcsBranch = revision.getRepositoryVersion().getVcsBranch();
    if (revisionVcsBranch == null || !PULL_REQUEST_BRANCH_PATTERN.matcher(revisionVcsBranch).matches()) {
      return revisionVcsBranch;
    }
    PullRequest pullRequest = getEndpoint().getPullRequest(revision, buildDescription);
    if (pullRequest == null) {
      return revisionVcsBranch;
    }
    return pullRequest.fromRef.id;
  }

  @Override
  public void processResponse(HttpHelper.HttpResponse response) throws HttpPublisherException {
    final int statusCode = response.getStatusCode();
    if (statusCode >= 400)
      throw new HttpPublisherException(statusCode, response.getStatusText(), parseErrorMessage(response));
  }

  @Nullable
  private String parseErrorMessage(@NotNull HttpHelper.HttpResponse response) {
    try {
      String str = response.getContent();
      if (str == null) {
        return null;
      }
      LOG.debug("Stash response: " + str);
      JsonElement json = new JsonParser().parse(str);
      if (!json.isJsonObject())
        return null;
      JsonObject jsonObj = json.getAsJsonObject();
      JsonElement errors = jsonObj.get("errors");
      if (errors == null || !errors.isJsonArray())
        return null;
      JsonArray errorsArray = errors.getAsJsonArray();
      if (errorsArray.size() == 0)
        return null;
      JsonElement error = errorsArray.get(0);
      if (error == null || !error.isJsonObject())
        return null;
      JsonElement msg = error.getAsJsonObject().get("message");
      return msg != null ? msg.getAsString() : null;
    } catch (JsonSyntaxException e) {
      return null;
    }
  }

  private String getBaseUrl() {
    return HttpHelper.stripTrailingSlash(myParams.get(Constants.STASH_BASE_URL));
  }

  private String getUsername() {
    return myParams.get(Constants.STASH_USERNAME);
  }

  private String getPassword() {
    return myParams.get(Constants.STASH_PASSWORD);
  }

  private BitbucketEndpoint getEndpoint() {
    if (myBitbucketEndpoint != null) return myBitbucketEndpoint;
    if (myBuildType instanceof BuildTypeEx && ((BuildTypeEx)myBuildType).getBooleanInternalParameter("commitStatusPublisher.enforceDeprecatedAPI")) {
      myBitbucketEndpoint = new BuildApiEndpoint();
      return myBitbucketEndpoint;
    }
    String serverVersion = getSettings().getServerVersion(getBaseUrl());
    if (VersionComparatorUtil.compare(serverVersion, SERVER_VERSION_BUILD_SERVER_HWM) < 0) {
      myBitbucketEndpoint = new BuildApiEndpoint();
    } else if (VersionComparatorUtil.compare(serverVersion, SERVER_VERSION_EXTENDED_SERVER_LWM) >= 0) {
      myBitbucketEndpoint = new ExtendedApiEndpoint();
    } else {
      myBitbucketEndpoint = new CoreApiEndpoint();
    }
    return myBitbucketEndpoint;
  }

  private interface StatusRequestData {
    @NotNull String getCommit();
    @NotNull String getKey();
    @Nullable VcsRootInstance getVcsRootInstance();
  }

  private class SBuildPromotionRequestData implements StatusRequestData {
    private final BuildPromotion myBuildPromotion;
    private final BuildRevision myRevision;

    public SBuildPromotionRequestData(@NotNull BuildPromotion buildPromotion, @NotNull BuildRevision revision) {
      myBuildPromotion = buildPromotion;
      myRevision = revision;
    }

    @NotNull
    @Override
    public String getCommit() {
      return myRevision.getRevision();
    }

    @NotNull
    @Override
    public String getKey() {
      return myBuildPromotion.getBuildTypeExternalId();
    }

    @Nullable
    @Override
    public VcsRootInstance getVcsRootInstance() {
      return myRevision.getRoot();
    }
  }

  private interface StatusData extends StatusRequestData {
    @NotNull StashBuildStatus getState();
    @NotNull String getName();
    @NotNull String getUrl();
    @NotNull String getDescription();
    @NotNull String getBuildNumber();
    long getBuildDurationMs();
    @Nullable String getVcsBranch();

    BuildStatistics getBuildStatistics();
  }

  private abstract class BaseBuildData implements StatusData {
    private final BuildRevision myRevision;
    private final StashBuildStatus myStatus;
    private final String myDescription;
    private final String myVcsBranch;

    BaseBuildData(@NotNull BuildRevision revision, @NotNull StashBuildStatus status, @NotNull String description, @Nullable String vcsBranch) {
      myRevision = revision;
      myStatus = status;
      myDescription = description;
      myVcsBranch = vcsBranch;
    }

    @NotNull
    @Override
    public String getCommit() {
      return myRevision.getRevision();
    }

    @NotNull
    @Override
    public StashBuildStatus getState() {
      return myStatus;
    }

    @NotNull
    @Override
    public String getDescription() {
      return myDescription;
    }

    @Nullable
    @Override
    public VcsRootInstance getVcsRootInstance() {
      return myRevision.getRoot();
    }

    @Nullable
    @Override
    public String getVcsBranch() {
      return myVcsBranch;
    }
  }

  private class SBuildData extends BaseBuildData implements StatusData {

    private final SBuild myBuild;
    private final BuildStatistics myBuildStatistics;

    SBuildData(@NotNull SBuild build, @NotNull BuildRevision revision, @NotNull StashBuildStatus status, @NotNull String description, @Nullable String vcsBranch) {
      super(revision, status, description, vcsBranch);
      myBuild = build;
      myBuildStatistics = myBuild.getBuildStatistics(BuildStatisticsOptions.ALL_TESTS_NO_DETAILS);
    }

    @NotNull
    @Override
    public String getKey() {
      return myBuild.getBuildPromotion().getBuildTypeExternalId();
    }

    @NotNull
    @Override
    public String getName() {
      return myBuild.getFullName() + " #" + myBuild.getBuildNumber();
    }

    @NotNull
    @Override
    public String getUrl() {
      return getViewUrl(myBuild);
    }

    @NotNull
    @Override
    public String getBuildNumber() {
      return myBuild.getBuildNumber();
    }

    @Override
    public long getBuildDurationMs() {
      return myBuild.getDuration() * 1000;
    }

    @Override
    public BuildStatistics getBuildStatistics() {
      return myBuildStatistics;
    }
  }

  private class SBuildPromotionData extends BaseBuildData implements StatusData {

    private final BuildPromotion myBuildPromotion;

    SBuildPromotionData(@NotNull BuildPromotion buildPromotion, @NotNull BuildRevision revision, @NotNull StashBuildStatus status, @NotNull String description, @Nullable String vcsBranch) {
      super(revision, status, description, vcsBranch);
      myBuildPromotion = buildPromotion;
    }

    @NotNull
    @Override
    public String getKey() {
      return myBuildPromotion.getBuildTypeExternalId();
    }

    @NotNull
    @Override
    public String getName() {
      return myBuildPromotion.getBuildType().getName();
    }

    @NotNull
    @Override
    public String getUrl() {
      return getViewUrl(myBuildPromotion);
    }

    @NotNull
    @Override
    public String getBuildNumber() {
      return "";
    }

    @Override
    public long getBuildDurationMs() {
      return 0;
    }

    @Override
    public BuildStatistics getBuildStatistics() {
      return null;
    }
  }

  private interface BitbucketEndpoint {
    void publishBuildStatus(@NotNull StatusData data, @NotNull String buildDescription);
    PullRequest getPullRequest(@NotNull BuildRevision revision, @NotNull String buildDescriptor);
    JsonStashBuildStatus getCommitBuildStatus(@NotNull StatusRequestData data, @NotNull String buildDescription);
  }

  private abstract class BaseBitbucketEndpoint implements BitbucketEndpoint {

    @Override
    public void publishBuildStatus(@NotNull StatusData data, @NotNull String buildDescription) {
      try {
        String url = getBuildEndpointUrl(data);
        postJson(url, getUsername(), getPassword(), createBuildStatusMessage(data), null, buildDescription);
      } catch (PublisherException ex) {
        myProblems.reportProblem("Commit Status Publisher has failed to prepare a request", StashPublisher.this, buildDescription, null, ex, LOG);
      }
    }

    @Override
    public PullRequest getPullRequest(BuildRevision revision, @NotNull String buildDescriptor) {
      AtomicReference<PullRequest> result = new AtomicReference<>(null);
      try {
        String url = getPullRequestEndpointUrl(revision);
        if (url == null) {
          LOG.debug("No endpoint URL is provided to get pull requests for revision " + revision.getRevision());
          return null;
        }
        LoggerUtil.logRequest(getId(), HttpMethod.GET, url, null);
        IOGuard.allowNetworkCall(() -> HttpHelper.get(url, getUsername(), getPassword(), null, DEFAULT_CONNECTION_TIMEOUT, getSettings().trustStore(), new DefaultHttpResponseProcessor() {
          @Override
          public void processResponse(HttpHelper.HttpResponse response) throws HttpPublisherException, IOException {
            super.processResponse(response);
            final String json = response.getContent();
            if (null == json) {
              throw new HttpPublisherException("Stash publisher has received no response");
            }
            PullRequest pullRequest = myGson.fromJson(json, PullRequest.class);
            if (null == pullRequest) {
              throw new HttpPublisherException("Stash publisher has received a malformed response");
            }
            result.set(pullRequest);
          }
        }));
      } catch (Exception e) {
        myProblems.reportProblem("Can not get pull request", StashPublisher.this, buildDescriptor, null, e, LOG);
      }
      return result.get();
    }

    @Override
    public JsonStashBuildStatus getCommitBuildStatus(@NotNull StatusRequestData data, @NotNull String buildDescription) {
      final String baseEndpointUrl = getBaseUrl() + "/rest/build-status/1.0/commits/" + data.getCommit();
      final ResponseEntityProcessor<DeprecatedJsonStashBuildStatuses> processor = new ResponseEntityProcessor<>(DeprecatedJsonStashBuildStatuses.class);
      int size = 25;
      int start = 0;
      DeprecatedJsonStashBuildStatuses statuses;
      do {
        statuses = doLoadStatuses(baseEndpointUrl, processor, start, size, buildDescription);
        if (statuses == null || statuses.values == null || statuses.values.isEmpty()) return null;
        Optional<DeprecatedJsonStashBuildStatuses.Status> desiredStatusOp = statuses.values.stream()
                                                                                           .filter(status -> data.getKey().equals(status.key))
                                                                                           .findFirst();
        if (desiredStatusOp.isPresent()) {
          return convertToActualStatus(desiredStatusOp.get());
        }
        start = statuses.nextPageStart != null ? statuses.nextPageStart : start + size;
      } while (!statuses.isLastPage);

      return null;
    }

    private DeprecatedJsonStashBuildStatuses doLoadStatuses(String baseUrl, ResponseEntityProcessor<DeprecatedJsonStashBuildStatuses> processor, int start, int size, String buildDescription) {
      String endpointUrl = String.format("%s?size=%d&start=%d", baseUrl, size, start);
      try {
        return get(endpointUrl, getUsername(), getPassword(), null, processor);
      } catch (PublisherException ex) {
        myProblems.reportProblem("Commit Status Publisher has failed to prepare a request", StashPublisher.this, buildDescription, null, ex, LOG);
        return null;
      }
    }

    private JsonStashBuildStatus convertToActualStatus(@Nullable DeprecatedJsonStashBuildStatuses.Status status) {
      if (status == null) {
        return null;
      }
      return new JsonStashBuildStatus(status);
    }

    protected abstract String getBuildEndpointUrl(final StatusRequestData data) throws PublisherException;
    protected abstract String getPullRequestEndpointUrl(final BuildRevision revision) throws PublisherException;

    @NotNull
    protected abstract String createBuildStatusMessage(@NotNull StatusData data);
  }

  private class BuildApiEndpoint extends BaseBitbucketEndpoint implements BitbucketEndpoint {

    @Override
    protected String getBuildEndpointUrl(final StatusRequestData data) {
      return getBaseUrl() + "/rest/build-status/1.0/commits/" + data.getCommit();
    }

    @Override
    protected String getPullRequestEndpointUrl(BuildRevision revision) {
      return null;
    }

    @NotNull
    @Override
    protected String createBuildStatusMessage(@NotNull final StatusData data) {
      Map<String, String> jsonData = new LinkedHashMap<String, String>();
      jsonData.put("state", data.getState().toString());
      jsonData.put("key", data.getKey());
      jsonData.put("parent", data.getKey());
      jsonData.put("name", data.getName());
      jsonData.put("url", data.getUrl());
      jsonData.put("description", data.getDescription());
      return myGson.toJson(jsonData);
    }
  }

  private class CoreApiEndpoint extends BaseBitbucketEndpoint implements BitbucketEndpoint {

    @Override
    protected String getBuildEndpointUrl(final StatusRequestData data) throws PublisherException {
      VcsRootInstance vcs = data.getVcsRootInstance();
      String commit = data.getCommit();
      Repository repo = getRepository(vcs, commit);
      return getBaseUrl() + "/rest/api/1.0/projects/" + repo.owner() + "/repos/" + repo.repositoryName() + "/commits/" + commit + "/builds" ;
    }

    @Override
    protected String getPullRequestEndpointUrl(BuildRevision revision) throws PublisherException {
      VcsRootInstance vcs = revision.getRoot();
      String commit = revision.getRepositoryVersion().getVersion();
      Repository repo = getRepository(vcs, commit);
      String pullRequestId = getPullRequestId(revision);
      return getBaseUrl() + "/rest/api/1.0/projects/" + repo.owner() + "/repos/" + repo.repositoryName() + "/pull-requests/" + pullRequestId;
    }

    private String getPullRequestId(BuildRevision revision) throws PublisherException {
      String revisionVcsBranch = revision.getRepositoryVersion().getVcsBranch();
      if (revisionVcsBranch == null) {
        throw new PublisherException("Can not get pull request, because VCS branch is unknown for revision: " + revision);
      }
      Matcher pullRequestIdMatcher = PULL_REQUEST_BRANCH_PATTERN.matcher(revisionVcsBranch);
      if (pullRequestIdMatcher.find()) {
        return pullRequestIdMatcher.group(1);
      }
      throw new PublisherException("Can not get pull request id from branch name: " + revisionVcsBranch);
    }

    private Repository getRepository(VcsRootInstance vcs, String commit) throws PublisherException {
      if (vcs == null)
        throw new PublisherException("No VCS root instance associated with the revision " + commit);
      String vcsUrl = vcs.getProperty("url");
      if (vcsUrl == null)
        throw new PublisherException("No VCS root fetch URL provided, revision " + commit);
      Repository repo = StashSettings.VCS_URL_PARSER.parseRepositoryUrl(vcsUrl);
      if (repo == null)
        throw new PublisherException("Failed to parse repoisotry fetch URL " + vcsUrl);
      return repo;
    }

    @NotNull
    @Override
    protected String createBuildStatusMessage(@NotNull final StatusData data) {
      BuildStatistics stats = data.getBuildStatistics();
      JsonStashBuildStatus.StashTestStatistics testResults;
      if (stats != null) {
        testResults = new JsonStashBuildStatus.StashTestStatistics();

        testResults.failed = stats.getFailedTestCount();
        testResults.skipped = stats.getMutedTestsCount() + stats.getIgnoredTestCount();
        testResults.successful = stats.getPassedTestCount();
      } else {
        testResults = null;
      }
      JsonStashBuildStatus status = new JsonStashBuildStatus(data.getBuildNumber(), data.getDescription(), data.getKey(), data.getKey(), data.getName(), data.getVcsBranch(),
                                                             data.getUrl(), data.getState().name(), data.getBuildDurationMs(), testResults);
      return myGson.toJson(status);
    }
  }

  private class ExtendedApiEndpoint extends CoreApiEndpoint {

    @Override
    public JsonStashBuildStatus getCommitBuildStatus(@NotNull StatusRequestData data, @NotNull String buildDescription) {
      try {
        String buildEndpointUrl = getBuildWithKeyEndpointUrl(data);
        ResponseEntityProcessor<JsonStashBuildStatus> processor = new ResponseEntityProcessor<JsonStashBuildStatus>(JsonStashBuildStatus.class) {
          @Override
          protected boolean handleError(@NotNull HttpHelper.HttpResponse response) throws HttpPublisherException {
            int statusCode = response.getStatusCode();
            if (statusCode >= 400) {
              if (statusCode == 404) return false;
              throw new HttpPublisherException(statusCode, response.getStatusText(), "HTTP response error");
            }
            return true;
          }
        };
        return get(buildEndpointUrl, getUsername(), getPassword(), null, processor);
      } catch (PublisherException ex) {
        myProblems.reportProblem("Commit Status Publisher has failed to prepare a request", StashPublisher.this, buildDescription, null, ex, LOG);
      }
      return null;
    }

    protected String getBuildWithKeyEndpointUrl(StatusRequestData data) throws PublisherException {
      String buildEndpointUrl = getBuildEndpointUrl(data);
      return buildEndpointUrl + "?key=" + data.getKey();
    }

  }

  private static class PullRequest {
    private long id;
    private String title;
    private boolean open, closed;
    private PullRequestRef fromRef, toRef;
  }

  private static class PullRequestRef {
    private String id, displayId, latestCommit;
  }
}
