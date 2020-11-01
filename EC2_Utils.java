package ec2;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.model.AuthorizeSecurityGroupEgressRequest;
import com.amazonaws.services.ec2.model.AuthorizeSecurityGroupIngressRequest;
import com.amazonaws.services.ec2.model.CreateKeyPairRequest;
import com.amazonaws.services.ec2.model.CreateKeyPairResult;
import com.amazonaws.services.ec2.model.CreateSecurityGroupRequest;
import com.amazonaws.services.ec2.model.CreateTagsRequest;
import com.amazonaws.services.ec2.model.DescribeInstanceStatusRequest;
import com.amazonaws.services.ec2.model.DescribeInstancesRequest;
import com.amazonaws.services.ec2.model.DescribeInstancesResult;
import com.amazonaws.services.ec2.model.DescribeKeyPairsResult;
import com.amazonaws.services.ec2.model.DescribeSecurityGroupsResult;
import com.amazonaws.services.ec2.model.IamInstanceProfileSpecification;
import com.amazonaws.services.ec2.model.InstanceType;
import com.amazonaws.services.ec2.model.IpPermission;
import com.amazonaws.services.ec2.model.IpRange;
import com.amazonaws.services.ec2.model.KeyPair;
import com.amazonaws.services.ec2.model.KeyPairInfo;
import com.amazonaws.services.ec2.model.Reservation;
import com.amazonaws.services.ec2.model.RunInstancesRequest;
import com.amazonaws.services.ec2.model.RunInstancesResult;
import com.amazonaws.services.ec2.model.SecurityGroup;
import com.amazonaws.services.ec2.model.StartInstancesRequest;
import com.amazonaws.services.ec2.model.StartInstancesResult;
import com.amazonaws.services.ec2.model.StopInstancesRequest;
import com.amazonaws.services.ec2.model.StopInstancesResult;
import com.amazonaws.services.ec2.model.Tag;
import com.amazonaws.services.ec2.model.TerminateInstancesRequest;
import com.amazonaws.services.ec2.model.TerminateInstancesResult;
import com.amazonaws.services.ec2.waiters.AmazonEC2Waiters;
import com.amazonaws.services.simplesystemsmanagement.AWSSimpleSystemsManagement;
import com.amazonaws.services.simplesystemsmanagement.model.CreateDocumentRequest;
import com.amazonaws.services.simplesystemsmanagement.model.DocumentIdentifier;
import com.amazonaws.services.simplesystemsmanagement.model.GetCommandInvocationRequest;
import com.amazonaws.services.simplesystemsmanagement.model.GetCommandInvocationResult;
import com.amazonaws.services.simplesystemsmanagement.model.ListDocumentsRequest;
import com.amazonaws.services.simplesystemsmanagement.model.ListDocumentsResult;
import com.amazonaws.services.simplesystemsmanagement.model.SendCommandRequest;
import com.amazonaws.services.simplesystemsmanagement.model.SendCommandResult;
import com.amazonaws.waiters.WaiterParameters;
import com.amazonaws.waiters.WaiterTimedOutException;
import com.amazonaws.waiters.WaiterUnrecoverableException;
import com.amazonaws.services.ec2.model.Instance;

public class EC2_Utils {

	public static String createEC2Instance(AmazonEC2 ec2, String name, String amiId) {
		
		createSecurityGroupIfNotExists(ec2, "securityGroupName");
		
		createKeyPairIfNotExists(ec2, "keyPairName");
		
		IamInstanceProfileSpecification iamInstanceProfile = new IamInstanceProfileSpecification().withName("roleName");
		
		//Create and launch the EC2 instance
		RunInstancesRequest runInstancesRequest = new RunInstancesRequest();

		runInstancesRequest.withImageId(amiId)
		                   .withInstanceType(InstanceType.T2Micro)
		                   .withMinCount(1)
		                   .withMaxCount(1)
		                   .withKeyName("keyPairName")
		                   .withSecurityGroups("securityGroupName")
		                   .withUserData(getUserData())
		                   .withIamInstanceProfile(iamInstanceProfile);
		
		RunInstancesResult run_response = ec2.runInstances(runInstancesRequest);
		String reservation_id = run_response.getReservation().getInstances().get(0).getInstanceId();
	     
		//To add the name to the instance
		Tag tag = new Tag()
				.withKey("Name")
				.withValue(name);

		CreateTagsRequest tag_request = new CreateTagsRequest()
				.withResources(reservation_id)
				.withTags(tag);

		ec2.createTags(tag_request);
        
		System.out.println("The Instance with id "+reservation_id+" is creating and starting...");
        
		//Wait until the instance is effectively running and status check is ok
		if(waitUntilIsReady(ec2, reservation_id)) return reservation_id;
		
		return "";
	}
	
	public static void startInstance(AmazonEC2 ec2, String instanceId) {
		
		if(checkIfInstanceExist(ec2, instanceId)){
			StartInstancesRequest startedInstancesRequest = new StartInstancesRequest()
	                .withInstanceIds(instanceId);
			
			StartInstancesResult result = ec2.startInstances(startedInstancesRequest);
			String startingInstanceId = result.getStartingInstances().get(0).getInstanceId();
			System.out.println("The Instance with id "+startingInstanceId+" is starting...");
			
			//Wait until the instance is effectively running and status check is ok
			waitUntilIsReady(ec2, startingInstanceId);
		}else {
			System.out.println("There are no instance with the given instanceId.");
			System.exit(1);
		}
		
	}
	
	public static void stopInstance(AmazonEC2 ec2, String instanceId) {
		
		if(checkIfInstanceExist(ec2, instanceId)){
			StopInstancesRequest stoppedInstancesRequest = new StopInstancesRequest()
	                .withInstanceIds(instanceId);
			
			StopInstancesResult result = ec2.stopInstances(stoppedInstancesRequest);
			String stoppingInstanceId = result.getStoppingInstances().get(0).getInstanceId();
			System.out.println("The Instance with id "+stoppingInstanceId+" is stopping...");
			
			//Wait until the instance is effectively stopped
			AmazonEC2Waiters waiter = new AmazonEC2Waiters(ec2);
			DescribeInstancesRequest describeRequest = new DescribeInstancesRequest();
			describeRequest.setInstanceIds(Arrays.asList(instanceId));
			WaiterParameters<DescribeInstancesRequest> params = new WaiterParameters<DescribeInstancesRequest>(describeRequest);
	     
	        try{
	         	waiter.instanceStopped().run(params);
	         	System.out.println("The instance is successfully stopped.");
	        	
	        } catch(AmazonServiceException | WaiterTimedOutException | WaiterUnrecoverableException e) {
	        	System.out.println("An exception is occurred during instance stopping.");
	        	System.exit(1);
	        }
		}else {
			System.out.println("There are no instance with the given instanceId.");
			System.exit(1);
		}
	}
	
	public static void terminateInstance(AmazonEC2 ec2, String instanceId) {
		
		if(checkIfInstanceExist(ec2, instanceId)){
			TerminateInstancesRequest terminateInstancesRequest = new TerminateInstancesRequest()
	                .withInstanceIds(instanceId);
			
			TerminateInstancesResult result = ec2.terminateInstances(terminateInstancesRequest);
		    String terminatingInstanceId = result.getTerminatingInstances().get(0).getInstanceId();
			System.out.println("The Instance with id "+terminatingInstanceId+" is shutting down...");
			
			//Wait until the instance is effectively terminated
			AmazonEC2Waiters waiter = new AmazonEC2Waiters(ec2);
			DescribeInstancesRequest describeRequest = new DescribeInstancesRequest();
	        describeRequest.setInstanceIds(Arrays.asList(instanceId));
	        WaiterParameters<DescribeInstancesRequest> params = new WaiterParameters<DescribeInstancesRequest>(describeRequest);
	     
	        try{
	        	waiter.instanceTerminated().run(params);
	            System.out.println("The instance is successfully terminated.");
	        	
	        } catch(AmazonServiceException | WaiterTimedOutException | WaiterUnrecoverableException e) {
	        	System.out.println("An exception is occurred during instance termination.");
	        	System.exit(1);
	        }
		}else {
			System.out.println("There are no instance with the given instanceId.");
			System.exit(1);
		}
	}
	
	private static boolean checkIfInstanceExist(AmazonEC2 ec2, String instanceId) {

		DescribeInstancesResult describeInstancesRequest = ec2.describeInstances();
        List<Reservation> reservations = describeInstancesRequest.getReservations();
		
        for (Reservation reservation : reservations) {
            for (Instance instance : reservation.getInstances()) {
            	//check if there is an instance with the given ID
            	if(instance.getInstanceId().equals(instanceId)) return true;
            }
        }
        
        return false;
	}
	
	private static void createSecurityGroupIfNotExists(AmazonEC2 ec2, String securityGroupName) {
		
		boolean exists = false;
		
		DescribeSecurityGroupsResult describeSecurityGroupsResult = ec2.describeSecurityGroups();
		List<SecurityGroup> securityGroups = describeSecurityGroupsResult.getSecurityGroups();
		
        for (SecurityGroup securityGroup : securityGroups) {
            	//check if your security group exists
            	if(securityGroup.getGroupName().equals(securityGroupName)) exists = true;
        }
        
        if(!exists) {
        	//The security group with the given name does not exist so create it
        	
        	CreateSecurityGroupRequest createSecurityGroupRequest = new CreateSecurityGroupRequest();
        	createSecurityGroupRequest.withGroupName(securityGroupName).withDescription("Security group description");
        	String groupId = ec2.createSecurityGroup(createSecurityGroupRequest).getGroupId();
        	
        	//Set Permissions
        	IpPermission ipPermission = new IpPermission();
        	IpRange ipRange1 = new IpRange().withCidrIp("0.0.0.0/0");
        	
        	// SSH Permissions
        	ipPermission.withIpv4Ranges(Arrays.asList(new IpRange[] {ipRange1}))
        				.withIpProtocol("tcp")
        				.withFromPort(22)
        				.withToPort(22);
       	 	
       	 	AuthorizeSecurityGroupIngressRequest authorizeSecurityGroupIngressRequest =	new AuthorizeSecurityGroupIngressRequest();
       	 	authorizeSecurityGroupIngressRequest.withGroupName(securityGroupName).withIpPermissions(ipPermission);
       	 	ec2.authorizeSecurityGroupIngress(authorizeSecurityGroupIngressRequest);
       	 	
       	 	AuthorizeSecurityGroupEgressRequest authorizeSecurityGroupEgressRequest = new AuthorizeSecurityGroupEgressRequest();
       	 	authorizeSecurityGroupEgressRequest.withGroupId(groupId).withIpPermissions(ipPermission);
       	 	ec2.authorizeSecurityGroupEgress(authorizeSecurityGroupEgressRequest);

       	 	// HTTP Permissions
       	 	ipPermission = new IpPermission();
     	
        	ipPermission.withIpv4Ranges(Arrays.asList(new IpRange[] {ipRange1}))
						.withIpProtocol("tcp")
						.withFromPort(80)
						.withToPort(80);
    	 	
       	 	authorizeSecurityGroupIngressRequest =	new AuthorizeSecurityGroupIngressRequest();
       	 	authorizeSecurityGroupIngressRequest.withGroupName(securityGroupName).withIpPermissions(ipPermission);
       	 	ec2.authorizeSecurityGroupIngress(authorizeSecurityGroupIngressRequest);
       	 	
       	 	authorizeSecurityGroupEgressRequest = new AuthorizeSecurityGroupEgressRequest();
       	 	authorizeSecurityGroupEgressRequest.withGroupId(groupId).withIpPermissions(ipPermission);
       	 	ec2.authorizeSecurityGroupEgress(authorizeSecurityGroupEgressRequest);
        }
	}
	
	private static void createKeyPairIfNotExists(AmazonEC2 ec2, String keyPairName) {
		
		boolean exists = false;
		
		DescribeKeyPairsResult describeKeyPairsResult = ec2.describeKeyPairs();
		List<KeyPairInfo> keyPairsInfo = describeKeyPairsResult.getKeyPairs();
		
        for (KeyPairInfo keyPair : keyPairsInfo) {
            	//check if your key pair exists
            	if(keyPair.getKeyName().equals(keyPairName)) exists = true;
        }
        
        if(!exists) {
       	 	//The key pair with the given name does not exist, so create it
       	 	CreateKeyPairRequest createKeyPairRequest = new CreateKeyPairRequest().withKeyName(keyPairName);
       	 	CreateKeyPairResult createKeyPairResult = ec2.createKeyPair(createKeyPairRequest);
       	 	
       	 	KeyPair keyPair = new KeyPair();	    	
       	 	keyPair = createKeyPairResult.getKeyPair();
       	 	
		   	String privateKey = keyPair.getKeyMaterial();
			 	File keyFile = new File(keyPairName);
			 	FileWriter fw;
			try {
				fw = new FileWriter(keyFile);
		   	 	fw.write(privateKey);
		   	 	fw.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
        }
	}

	
  	private static String getUserData() {
		//These commands will be executed at the instance boot
  		String userData = "";
  		userData += "#!/bin/bash" + "\n"
  				+ "apt update" + "\n"
  				+ "apt -y install default-jre" + "\n"
  				+ "apt -y install unzip" + "\n"
  				+ "curl 'https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip' -o 'awscliv2.zip'" + "\n"
  				+ "unzip awscliv2.zip" + "\n"
  				+ "sudo ./aws/install" + "\n"
  				+ "cd home/ubuntu" + "\n";
  		
  		String base64UserData = null;
  		try {
  		    base64UserData = new String(Base64.getEncoder().encode(userData.getBytes("UTF-8")), "UTF-8");
  		} catch (UnsupportedEncodingException e) {
  		    e.printStackTrace();
  		}
  		return base64UserData;
  	}
	
	private static boolean waitUntilIsReady(AmazonEC2 ec2, String instanceId) {
		
		AmazonEC2Waiters waiter = new AmazonEC2Waiters(ec2);
		
        DescribeInstancesRequest describeRequest = new DescribeInstancesRequest();
        describeRequest.setInstanceIds(Arrays.asList(instanceId));
        WaiterParameters<DescribeInstancesRequest> params = new WaiterParameters<DescribeInstancesRequest>(describeRequest);
        
        DescribeInstanceStatusRequest describeStatusRequest = new DescribeInstanceStatusRequest();
        describeStatusRequest.setInstanceIds(Arrays.asList(instanceId));
        WaiterParameters<DescribeInstanceStatusRequest> paramsStatus = new WaiterParameters<DescribeInstanceStatusRequest>(describeStatusRequest);
        
        try{
    		//Wait until the instance is effectively running
        	waiter.instanceRunning().run(params);
            System.out.println("The instance is running...waiting for status checks...");
        	
        } catch(AmazonServiceException | WaiterTimedOutException | WaiterUnrecoverableException e) {
        	System.out.println("An exception is occurred during istanceRunning check.");
        	return false;
        }
        
        try{
        	//Now the instance is running, so waiting for status check
        	waiter.instanceStatusOk().run(paramsStatus);
            System.out.println("Status checked successfully.");
        	
        } catch(AmazonServiceException | WaiterTimedOutException | WaiterUnrecoverableException e) {
        	System.out.println("An exception is occurred during status check.");
        	return false;
        }
			        
		return true;
	}
	
	public static void runCommand(AWSSimpleSystemsManagement ssm, String instanceId) {
		
		String docName = "SM_document_name";
		boolean exists = false;

		//Check if a document with the given name is already present, otherwise create it
		String nextToken = null;

	    do {
	        ListDocumentsRequest request = new ListDocumentsRequest().withNextToken(nextToken);
	        ListDocumentsResult results = ssm.listDocuments(request);
	        
	        List<DocumentIdentifier> docs = results.getDocumentIdentifiers();

            for (DocumentIdentifier doc : docs) {
            	if(doc.getName().equals(docName)) {
            		exists = true;
            		break;
            	}
            }
	        
            nextToken = results.getNextToken();
	    } while (nextToken != null);

	    if(!exists) {
			//The document does not exist so you have to create it
			try {
		      createDocumentMethod(ssm, getDocumentContent(), docName);
		    }
		    catch (IOException e) {
		      e.printStackTrace();
		    }
		}
		
		//Send the commands described in the document
		SendCommandRequest sendCommandRequest = new SendCommandRequest()
				.withInstanceIds(instanceId)
				.withDocumentName(docName);
		
		SendCommandResult sendCommandResult = ssm.sendCommand(sendCommandRequest);
		String commandId = sendCommandResult.getCommand().getCommandId();
				
		GetCommandInvocationRequest getCommandInvocationRequest = new GetCommandInvocationRequest();
		getCommandInvocationRequest.setCommandId(commandId);
		getCommandInvocationRequest.setInstanceId(instanceId);
		
		//wait until the command is executed
		/*The waiter exists (AWSSimpleSystemsManagementWaiters) but it seems not be present in the library
		so I check if the command is executed and terminated manually
		 */
		GetCommandInvocationResult getCommandInvocationResult = new GetCommandInvocationResult();
		while(true) {
			getCommandInvocationResult = ssm.getCommandInvocation(getCommandInvocationRequest);
			String status = getCommandInvocationResult.getStatusDetails();
			if(status.equals("Success")) {
				System.out.println("\nThe output of the command executed is the following:");
				System.out.println(getCommandInvocationResult.getStandardOutputContent());
				break;
			}else if(status.equals("InProgress") || status.equals("Delayed") || status.equals("Pending")) {
				try {
					Thread.sleep(5000);
					continue;
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}else {
				System.out.println("There are problems executing this command, the status is:  "+ status);
				System.out.println(getCommandInvocationResult.getStandardErrorContent());
				break;
			}
		}
		
	}
	
	private static String getDocumentContent() throws IOException {
	      String filepath = new String("SM_document_filename.json");
	      byte[] encoded = Files.readAllBytes(Paths.get(filepath));
	        String documentContent = new String(encoded, StandardCharsets.UTF_8);
	        return documentContent;
	}
	    
	public static void createDocumentMethod (AWSSimpleSystemsManagement ssm, final String documentContent, String docName) {
	      final CreateDocumentRequest createDocRequest = new CreateDocumentRequest()
	    		  .withContent(documentContent)
	    		  .withName(docName)
	    		  .withDocumentType("Command")
	    		  .withDocumentFormat("JSON"); //The alternative is YAML
	      
	      ssm.createDocument(createDocRequest);
	    }
	
}




