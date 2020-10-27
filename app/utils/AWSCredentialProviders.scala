package utils

import com.amazonaws.auth.profile.ProfileCredentialsProvider
import com.amazonaws.auth.{AWSCredentialsProviderChain, DefaultAWSCredentialsProviderChain}

object AWSCredentialProviders extends Logging {
  def profileCredentialsProvider(profileName: String) = {
    log.info(s"Using $profileName profile credentials")
    new ProfileCredentialsProvider(profileName)
  }

  def deployToolsCredentialsProviderChain: AWSCredentialsProviderChain = new AWSCredentialsProviderChain(
    profileCredentialsProvider("deployTools"),
    new DefaultAWSCredentialsProviderChain()
  )
}
