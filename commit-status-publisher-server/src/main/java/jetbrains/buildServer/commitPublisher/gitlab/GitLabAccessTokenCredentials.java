package jetbrains.buildServer.commitPublisher.gitlab;

import jetbrains.buildServer.vcshostings.http.credentials.HttpCredentials;
import jetbrains.buildServer.vcshostings.http.credentials.AccessTokenCredentials;
import jetbrains.buildServer.serverSide.oauth.OAuthToken;
import jetbrains.buildServer.serverSide.oauth.OAuthTokensStorage;
import jetbrains.buildServer.util.HTTPRequestBuilder;
import org.jetbrains.annotations.NotNull;

public class GitLabAccessTokenCredentials extends AccessTokenCredentials implements HttpCredentials {

  private static final String HEADER_AUTHORIZATION = "Private-Token";

  private static final String OAUTH_HEADER_AUTHORIZATION = "Authorization";
  private static final String AUTHORIZATION_BEARER_PREFIX = "Bearer ";

  public GitLabAccessTokenCredentials(@NotNull final String token) {
    super(token);
  }

  public GitLabAccessTokenCredentials(@NotNull final String tokenId, @NotNull final OAuthToken refreshableToken, @NotNull final String vcsRootExtId, @NotNull final OAuthTokensStorage tokensStorage) {
    super(tokenId, refreshableToken, vcsRootExtId, tokensStorage);
  }

  @Override
  public void set(@NotNull final HTTPRequestBuilder requestBuilder) {
    if (!isRefreshable()) {
      requestBuilder.withHeader(HEADER_AUTHORIZATION, getToken());
    } else {
      requestBuilder.withHeader(OAUTH_HEADER_AUTHORIZATION, AUTHORIZATION_BEARER_PREFIX + getToken());
    }
  }
}
