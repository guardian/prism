package conf

import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration
import software.amazon.awssdk.core.retry.RetryPolicy

object AwsClientConfig {
  val clientConfig: ClientOverrideConfiguration = ClientOverrideConfiguration
    .builder()
    .retryPolicy(
      RetryPolicy
        .builder()
        .numRetries(10)
        .build()
    )
    .build()
}
