package utils

import com.amazonaws.auth.profile.{ProfileCredentialsProvider => ProfileCredentialsProviderV1}
import com.amazonaws.auth.{AWSCredentialsProviderChain, DefaultAWSCredentialsProviderChain}
import software.amazon.awssdk.auth.credentials.{AwsCredentialsProviderChain, DefaultCredentialsProvider, ProfileCredentialsProvider => ProfileCredentialsProviderV2}

object AWSCredentialProviders extends Logging {
  def profileCredentialsProvider(profileName: String) = {
    log.info(s"Using $profileName profile credentials")
    new ProfileCredentialsProviderV1(profileName)
  }

  private val deployToolsProfile = "deployTools"

  def deployToolsCredentialsProviderChain: AWSCredentialsProviderChain = new AWSCredentialsProviderChain(
    profileCredentialsProvider(deployToolsProfile),
    new DefaultAWSCredentialsProviderChain()
  )

  def deployToolsCredentialsProviderChainV2: AwsCredentialsProviderChain = AwsCredentialsProviderChain.of(
    ProfileCredentialsProviderV2.builder().profileName(deployToolsProfile).build(),
    DefaultCredentialsProvider.create()
  )
}
