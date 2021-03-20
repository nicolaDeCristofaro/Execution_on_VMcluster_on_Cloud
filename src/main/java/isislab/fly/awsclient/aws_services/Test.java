package isislab.fly.awsclient.aws_services;

import java.io.File;

import com.amazonaws.auth.BasicAWSCredentials;

public class Test {

	public static void main(String[] args) {
		
		BasicAWSCredentials creds = new BasicAWSCredentials("", "");
		String region = "eu-west-2";
		String amiID = "ami-005383956f2e5fb96";
		String securityGroupName = "my-security-group";
		String keyPairName = "MyKeyPair";
		String instance_type = "t2.micro";
		String purchasingOption = "on-demand";
		boolean persistent = false;
		
		AWSClient awsClient = new AWSClient(creds, region);
		
		//Create S3 Bucket with the given name if not exists, otherwise take the existent
		String bucketName = awsClient.createS3Bucket("exec-bucket-pi");
		
		File helloWorldZipProject = new File("HelloWorldonVm.jar");
		awsClient.uploadProjectToExecuteOnS3(bucketName, helloWorldZipProject);
		
		//Launch an onDemand or Spot Instance		
		String instanceId = awsClient.createEC2Instance("VM_"+helloWorldZipProject.getName(), amiID, securityGroupName, keyPairName, instance_type, bucketName, purchasingOption, persistent);
		
		//Execute project uploaded to S3 on VM
		awsClient.runOnVM(instanceId, bucketName, helloWorldZipProject.getName());
	
		//Terminate the instance
		awsClient.terminateEC2Instance(instanceId);
		
		System.exit(0);
	}

}
