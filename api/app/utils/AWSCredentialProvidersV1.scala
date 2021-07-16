package utils

import com.amazonaws.auth.profile.{ProfileCredentialsProvider => ProfileCredentialsProviderV1}
import com.amazonaws.auth.{AWSCredentialsProviderChain, DefaultAWSCredentialsProviderChain}
import utils.AWSCredentialProviders.log

object AWSCredentialProvidersV1 {
  def profileCredentialsProvider(profileName: String) = {
    log.info(s"Using $profileName profile credentials")
    new ProfileCredentialsProviderV1(profileName)
  }

  def deployToolsCredentialsProviderChain: AWSCredentialsProviderChain = new AWSCredentialsProviderChain(
    profileCredentialsProvider(AWSCredentialProviders.deployToolsProfile),
    new DefaultAWSCredentialsProviderChain
  )
}
