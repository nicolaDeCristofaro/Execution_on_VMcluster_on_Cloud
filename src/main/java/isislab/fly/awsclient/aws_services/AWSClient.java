package isislab.fly.awsclient.aws_services;

import java.io.File;

import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.AmazonEC2ClientBuilder;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.transfer.TransferManager;
import com.amazonaws.services.s3.transfer.TransferManagerBuilder;
import com.amazonaws.services.simplesystemsmanagement.AWSSimpleSystemsManagement;
import com.amazonaws.services.simplesystemsmanagement.AWSSimpleSystemsManagementClientBuilder;

public class AWSClient {
	
	private static AmazonEC2 ec2;
	private static AmazonS3 s3;
	private static TransferManager tm;
	private static AWSSimpleSystemsManagement ssm;
	private EC2Handler ec2Handler;
	private S3Handler s3Handler;
	private RunCommandHandler runCommandHandler;
	
	public AWSClient(BasicAWSCredentials creds, String region) {
		init(creds, region);
	}

	private void init(BasicAWSCredentials creds, String region) {
		
		ec2 = AmazonEC2ClientBuilder.standard()
				.withRegion(region)
				.withCredentials(new AWSStaticCredentialsProvider(creds))
				.build();
		
		s3 = AmazonS3ClientBuilder.standard()
				.withRegion(region)
				.withCredentials(new AWSStaticCredentialsProvider(creds))
				.build();
				
		tm = TransferManagerBuilder.standard()
				.withS3Client(s3)
				.build();
			
		ssm = AWSSimpleSystemsManagementClientBuilder.standard()
				.withRegion(region)							 
				.withCredentials(new AWSStaticCredentialsProvider(creds))
				.build();
		
		runCommandHandler = new RunCommandHandler(ssm, tm);
		ec2Handler = new EC2Handler(ec2, runCommandHandler);
		s3Handler = new S3Handler(s3, tm);

	}
	
	 
	 public String createS3Bucket(String bucketName) {
		 
		 return s3Handler.createBucket(bucketName);
	 }
	 
	 public void uploadProjectToExecuteOnS3(String bucketName, File ziProject) {
		 
		  s3Handler.uploadProjectToExecuteOnS3(bucketName, ziProject);
	 }
	 
	 public String createEC2Instance(String name, String amiId, String securityGroupName, String keyPairName, String instance_type, 
				String bucketNameToSync, String purchasingOption, boolean persistent) {
			
			String instanceId =  ec2Handler.createEC2Instance(name, amiId, securityGroupName, keyPairName, 
					instance_type, bucketNameToSync, purchasingOption, persistent);			
			
			return instanceId;

	}
	 
	 public void runOnVM(String instanceId, String bucketName, String projectName) {
		 
		 if (projectName.equals("")) {
			 //Uploaded file to allow FLY execution not existent
			 System.out.println("\n**ERROR: Uploaded project to allow execution not existent, check you S3 Bucket.");
			 System.exit(1);
		 }
		 
		 runCommandHandler.runOnVM(instanceId, bucketName, projectName);
		 
	 }

	 public void terminateEC2Instance(String instanceId) {
		 
		 ec2Handler.terminateInstance(instanceId);
	 }
	
	 
}
