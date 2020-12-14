package utils

import com.amazonaws.auth.profile.{ProfileCredentialsProvider => ProfileCredentialsProviderV1}
import com.amazonaws.auth.{AWSCredentialsProviderChain, DefaultAWSCredentialsProviderChain}
import software.amazon.awssdk.auth.credentials.{AwsCredentialsProviderChain, DefaultCredentialsProvider, ProfileCredentialsProvider => ProfileCredentialsProvider}

object AWSCredentialProviders extends Logging {

  def profileCredentialsProvider(profileName: String): AwsCredentialsProviderChain = {
    log.info(s"Using $profileName profile credentials")
    AwsCredentialsProviderChain.of(
      ProfileCredentialsProvider.builder.profileName(profileName).build
    )
  }

  private val deployToolsProfile = "deployTools"

  def deployToolsCredentialsProviderChain: AwsCredentialsProviderChain = AwsCredentialsProviderChain.of(
    ProfileCredentialsProvider.builder.profileName(deployToolsProfile).build,
    DefaultCredentialsProvider.create
  )

  def profileCredentialsProviderV1(profileName: String) = {
    log.info(s"Using $profileName profile credentials")
    new ProfileCredentialsProviderV1(profileName)
  }

  def deployToolsCredentialsProviderChainV1: AWSCredentialsProviderChain = new AWSCredentialsProviderChain(
    profileCredentialsProviderV1(deployToolsProfile),
    new DefaultAWSCredentialsProviderChain
  )


}
