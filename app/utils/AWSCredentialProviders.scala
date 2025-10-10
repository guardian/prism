package utils

import software.amazon.awssdk.auth.credentials.{
  AwsCredentialsProviderChain,
  DefaultCredentialsProvider,
  ProfileCredentialsProvider
}

object AWSCredentialProviders extends Logging {

  def profileCredentialsProvider(
      profileName: String
  ): AwsCredentialsProviderChain = {
    log.info(s"Using $profileName profile credentials")
    AwsCredentialsProviderChain.of(
      ProfileCredentialsProvider.builder.profileName(profileName).build
    )
  }

  private val deployToolsProfile = "deployTools"

  def deployToolsCredentialsProviderChain: AwsCredentialsProviderChain =
    AwsCredentialsProviderChain.of(
      ProfileCredentialsProvider.builder.profileName(deployToolsProfile).build,
      DefaultCredentialsProvider.builder.build
    )
}
