package jetbrains.buildServer.swarm.web;

import java.util.Arrays;
import java.util.HashMap;
import jetbrains.buildServer.BaseWebTestCase;
import jetbrains.buildServer.buildTriggers.vcs.BuildRevisionBuilder;
import jetbrains.buildServer.commitPublisher.MockPluginDescriptor;
import jetbrains.buildServer.controllers.MockRequest;
import jetbrains.buildServer.messages.Status;
import jetbrains.buildServer.serverSide.SFinishedBuild;
import jetbrains.buildServer.swarm.SwarmClient;
import jetbrains.buildServer.swarm.SwarmClientManager;
import jetbrains.buildServer.vcs.VcsRootInstance;
import jetbrains.buildServer.vcs.impl.SVcsRootImpl;
import org.mockito.Mockito;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import static jetbrains.buildServer.swarm.web.SwarmBuildPageExtension.SWARM_BEAN;
import static jetbrains.buildServer.swarm.web.SwarmBuildPageExtension.SWARM_REVIEWS_ENABLED;
import static org.assertj.core.api.BDDAssertions.then;

@Test
public class SwarmBuildPageExtensionTest extends BaseWebTestCase {

  @BeforeMethod
  @Override
  protected void setUp() throws Exception {
    super.setUp();

    setInternalProperty(SWARM_REVIEWS_ENABLED, "true");
  }

  @Test
  public void should_not_be_available_without_swarm_feature() throws Exception {
    SwarmBuildPageExtension extension =
      new SwarmBuildPageExtension(myServer, myWebManager, new MockPluginDescriptor(), new SwarmClientManager(myWebLinks, () -> null));

    SFinishedBuild build = createBuild(Status.NORMAL);
    MockRequest buildRequest = new MockRequest("buildId", String.valueOf(build.getBuildId()));
    then(extension.isAvailable(buildRequest)).isFalse();

    HashMap<String, Object> model = new HashMap<>();
    extension.fillModel(model, buildRequest, build);
    
    then(((SwarmBuildDataBean)model.get(SWARM_BEAN)).isEmpty()).isTrue();
  }
  
  @Test
  public void should_provide_reviews_data() throws Exception {
    // Given there is a build configuration with perforce VCS Root
    // and with associated Swarm build feature,
    // When swarm client returns associated reviews for the build Perforce changelist ID
    // Then those reviews should be present in the JSP model bean

    SVcsRootImpl perforce = myFixture.addVcsRoot("perforce", "");

    SwarmClientManager mockSwarmManager = Mockito.mock(SwarmClientManager.class);

    SwarmClient mockSwarmClient = Mockito.mock(SwarmClient.class);
    Mockito.when(mockSwarmClient.getSwarmServerUrl())
           .thenReturn("http://swarm-root/");
    Mockito.when(mockSwarmClient.getReviewIds(Mockito.eq("12321"), Mockito.any()))
           .thenReturn(Arrays.asList(380l, 382l, 381l));

    VcsRootInstance vri = myBuildType.getVcsRootInstanceForParent(perforce);
    Mockito.when(mockSwarmManager.getSwarmClient(myBuildType, vri))
           .thenReturn(mockSwarmClient);

    SwarmBuildPageExtension extension = new SwarmBuildPageExtension(myServer, myWebManager, new MockPluginDescriptor(), mockSwarmManager);

    SFinishedBuild build = build().in(myBuildType)
                                  .withBuildRevisions(BuildRevisionBuilder.buildRevision(vri, "12321"))
                                  .finish();

    HashMap<String, Object> model = new HashMap<>();
    MockRequest buildRequest = new MockRequest("buildId", String.valueOf(build.getBuildId()));
    then(extension.isAvailable(buildRequest)).isTrue();
    extension.fillModel(model, buildRequest, build);

    SwarmBuildDataBean bean = (SwarmBuildDataBean)model.get(SWARM_BEAN);
    then((bean).isEmpty()).isFalse();
    then(bean.getReviews().size()).isEqualTo(1);
    then(bean.getReviews().get(0).getUrl()).isEqualTo("http://swarm-root");
    then(bean.getReviews().get(0).getReviewIds()).containsExactly(380l, 381l, 382l);
  }

  // todo: UI list of reviews
  // todo: performance/caching of the retrieved reviews.
}
