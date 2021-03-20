package isislab.fly.awsclient.aws_services;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.model.AuthorizeSecurityGroupEgressRequest;
import com.amazonaws.services.ec2.model.AuthorizeSecurityGroupIngressRequest;
import com.amazonaws.services.ec2.model.CancelSpotInstanceRequestsRequest;
import com.amazonaws.services.ec2.model.CreateKeyPairRequest;
import com.amazonaws.services.ec2.model.CreateKeyPairResult;
import com.amazonaws.services.ec2.model.CreateSecurityGroupRequest;
import com.amazonaws.services.ec2.model.CreateTagsRequest;
import com.amazonaws.services.ec2.model.DescribeInstanceStatusRequest;
import com.amazonaws.services.ec2.model.DescribeInstancesRequest;
import com.amazonaws.services.ec2.model.DescribeInstancesResult;
import com.amazonaws.services.ec2.model.DescribeKeyPairsResult;
import com.amazonaws.services.ec2.model.DescribeSecurityGroupsResult;
import com.amazonaws.services.ec2.model.GroupIdentifier;
import com.amazonaws.services.ec2.model.IamInstanceProfileSpecification;
import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.InstanceType;
import com.amazonaws.services.ec2.model.IpPermission;
import com.amazonaws.services.ec2.model.IpRange;
import com.amazonaws.services.ec2.model.KeyPair;
import com.amazonaws.services.ec2.model.KeyPairInfo;
import com.amazonaws.services.ec2.model.Reservation;
import com.amazonaws.services.ec2.model.SecurityGroup;
import com.amazonaws.services.ec2.model.Tag;
import com.amazonaws.services.ec2.model.TerminateInstancesRequest;
import com.amazonaws.services.ec2.model.TerminateInstancesResult;
import com.amazonaws.services.ec2.waiters.AmazonEC2Waiters;
import com.amazonaws.waiters.WaiterParameters;
import com.amazonaws.waiters.WaiterTimedOutException;
import com.amazonaws.waiters.WaiterUnrecoverableException;

public class EC2Handler {
	
	private AmazonEC2 ec2;
	private OnDemandInstancesHandler onDemandHandler;
	private SpotInstancesHandler spotHandler;
	private RunCommandHandler runCommandHandler;
	private boolean persistent;
    
    public EC2Handler (AmazonEC2 ec2, RunCommandHandler runCommandHandler) {
    	this.ec2 = ec2;
    	this.runCommandHandler = runCommandHandler;
        onDemandHandler = new OnDemandInstancesHandler(ec2);
		spotHandler = new SpotInstancesHandler(ec2);

    }
    
    public String createEC2Instance(String name, String amiId, String securityGroupName, String keyPairName, String instance_type, 
			String bucketNameToSync, String purchasingOption, boolean persistent) {
    	
    	if (!checkCorrectInstanceType(instance_type)) System.exit(1);  //Instance_type not correct
        	
    	//Check if a new vm has to be created or we could use an existent one
    	this.persistent = persistent;
    	String persistenceInstanceId = checkVmPersistence(name, persistent, amiId, securityGroupName, keyPairName, instance_type, purchasingOption);
    	
    	if( persistenceInstanceId.contains("toTerminate")) {
    		//Terminate the existent Vm for the execution because not appropriate
    		System.out.println("\n\u27A4 The existent VM hasn't the characteristics requested, so terminate it, and then create a new one...");
    		terminateInstance(persistenceInstanceId);
    	}else if (!persistenceInstanceId.equals("")) {
    		//An existent Vm for the execution with requested characteristics exists so use it
    		System.out.println("\n\u27A4 Using existent VM with instance-id: "+persistenceInstanceId);
    		//Syncing with S3 Bucket
    		runCommandHandler.syncS3Bucket(persistenceInstanceId, bucketNameToSync);
    		return persistenceInstanceId;
    	}

		createSecurityGroupIfNotExists(securityGroupName);
		createKeyPairIfNotExists(keyPairName);
		
		IamInstanceProfileSpecification iamInstanceProfile = new IamInstanceProfileSpecification().withName("role_for_ec2");
		String instanceId="";
		
		//Check purchasing option: on-demand or spot
		if( purchasingOption.equals("on-demand")) {
			
			instanceId = onDemandHandler.createOnDemandInstance(name, amiId, securityGroupName, keyPairName, 
					instance_type, bucketNameToSync, iamInstanceProfile);
			
		}else if( purchasingOption.equals("spot")) {
			
			instanceId = spotHandler.createSpotInstance(name, amiId, securityGroupName, keyPairName, 
					instance_type, bucketNameToSync, iamInstanceProfile);
		}
		
		//To add the name to the instance
		Tag tag = new Tag()
			.withKey("Name")
			.withValue(name);

		CreateTagsRequest tag_request = new CreateTagsRequest()
		    .withResources(instanceId)
		    .withTags(tag);

		ec2.createTags(tag_request);
		
		System.out.println("   \u2022 The Instance with id "+instanceId+" is creating and starting...");
		
		//Wait until the instance is effectively running and status check is ok
		waitUntilIsReady(instanceId);
		
		//Wait for instance boot to complete 
		runCommandHandler.waitForInstanceBoot(instanceId);
			
		return instanceId;
	}
    
    private boolean checkCorrectInstanceType( String instance_type) {
    	if (instance_type == null || "".equals(instance_type)) {
            System.out.println("\n**ERROR: Instance_type cannot be null or empty!");
            return false;
        }

        for (InstanceType enumEntry : InstanceType.values()) {
            if (enumEntry.toString().equals(instance_type)) {
                return true;
            }
        }

        System.out.println("\n**ERROR: Instance_type chosen: " + instance_type + " not existent or not available in the region selected.");
        return false;
    }
 
    private void createSecurityGroupIfNotExists(String securityGroupName) {

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
			createSecurityGroupRequest.withGroupName(securityGroupName).withDescription("fly security group");
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
		
	private void createKeyPairIfNotExists(String keyPairName) {
			
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
	
	public void terminateInstance(String instanceId) {
		
		String instanceIdToTerminate = instanceId;
		boolean terminateVmNotMatching = false;
		if(instanceIdToTerminate.contains("toTerminate")) {
			instanceIdToTerminate = instanceIdToTerminate.replace("-toTerminate","");
			terminateVmNotMatching = true;
		}
		if(checkIfInstanceExist(instanceIdToTerminate, "").getInstanceId() != null){
			
			if(this.persistent && !terminateVmNotMatching) { 
				//Don't terminate the instance but clear the content inside it
	    		System.out.println("\n\u27A4 The vm with instanceId: "+instanceIdToTerminate+" is still running and it is ready for the next execution.");
				return;
			}
			
			//Terminate the instance permanently
			TerminateInstancesRequest terminateInstancesRequest = new TerminateInstancesRequest()
					.withInstanceIds(instanceIdToTerminate);
			
			TerminateInstancesResult result = ec2.terminateInstances(terminateInstancesRequest);
		    String terminatingInstanceId = result.getTerminatingInstances().get(0).getInstanceId();
		    System.out.println("\n\u27A4 VM shutting down...");
			System.out.println("   \u2022 The Instance with id "+terminatingInstanceId+" is shutting down...");
			
			//Wait until the instance is effectively terminated
			AmazonEC2Waiters waiter = new AmazonEC2Waiters(ec2);
			DescribeInstancesRequest describeRequest = new DescribeInstancesRequest();
			describeRequest.setInstanceIds(Arrays.asList(instanceIdToTerminate));
			WaiterParameters<DescribeInstancesRequest> params = new WaiterParameters<DescribeInstancesRequest>(describeRequest);
	     
			try{
				waiter.instanceTerminated().run(params);
			    System.out.println("   \u2022 The instance is successfully terminated.");
				
			} catch(AmazonServiceException | WaiterTimedOutException | WaiterUnrecoverableException e) {
				System.out.println("   \u2022 An exception is occurred during instance termination.");
				System.exit(1);
			}
			
			//check if it is a spot instance
			String spotInstanceRequest = checkIfInstanceIsSpot(instanceId);
			if (!spotInstanceRequest.equals("")) {
				//delete also the spot request
				try {
		            // Cancel requests.
		            System.out.println("   \u2022 Cancelling spot request.");
		            ArrayList<String> spotInstanceRequestIds = new ArrayList<String>();
		            spotInstanceRequestIds.add(spotInstanceRequest);

		            CancelSpotInstanceRequestsRequest cancelRequest = new CancelSpotInstanceRequestsRequest(spotInstanceRequestIds);
		            ec2.cancelSpotInstanceRequests(cancelRequest);
		            System.out.println("   \u2022 Spot request associated cancelled.");
		        } catch (AmazonServiceException e) {
		            // Write out any exceptions that may have occurred.
		            System.out.println("Error cancelling instances");
		            System.out.println("Caught Exception: " + e.getMessage());
		            System.out.println("Reponse Status Code: " + e.getStatusCode());
		            System.out.println("Error Code: " + e.getErrorCode());
		            System.out.println("Request ID: " + e.getRequestId());
		        }
			}
			
		}else {
			System.out.println("   \u2022 There are no instance with the given instanceId.");
			System.exit(1);
		}
	}
	
	private Instance checkIfInstanceExist(String instanceId, String vmName) {
		
		Instance i = new Instance();

		DescribeInstancesResult describeInstancesRequest = ec2.describeInstances();
		List<Reservation> reservations = describeInstancesRequest.getReservations();
			
		for (Reservation reservation : reservations) {
		    for (Instance instance : reservation.getInstances()) {
		    	if(!instanceId.equals("")) {
			    	//check if there is an instance with the given ID
			    	if(instance.getInstanceId().equals(instanceId)) return instance;
		    	}else if(!vmName.equals("")) {
			    	//check if there is an instance with the given name
			    	if(instance.getState().getName().equals("running") && instance.getTags() != null) {
			    		for (Tag tag : instance.getTags()) {
			    			if( tag.getKey().equals("Name") && tag.getValue().equals(vmName)) return instance;
			            }
			    	}
		    	}
		    }
		}
		
		return i;
	}
	
	private boolean checkInstanceCharacteristics(Instance i, String amiId, String securityGroupName, String keyPairName, String instance_type,
			String purchasingOption) {
		
		boolean fullCheckDone = false;

		//Check if the requested characteristics match the existent instance
		if( i.getImageId().equals(amiId) &&
			i.getKeyName().equals(keyPairName) &&
			i.getInstanceType().equals(instance_type)) {
				
			//Check security group name
			List<GroupIdentifier> sc = i.getSecurityGroups();
			for(GroupIdentifier g : sc) {
				if (g.getGroupName().equals(securityGroupName)) {
					//Last check: purchasing option -> spot or ond-demand
					if ( (checkIfInstanceIsSpot(i.getInstanceId()).equals("") && purchasingOption.equals("on-demand")) ||
						(!checkIfInstanceIsSpot(i.getInstanceId()).equals("") && purchasingOption.equals("spot"))	) fullCheckDone = true;
				}
			}
		}
		return fullCheckDone;
	}
	
	private String checkVmPersistence(String vmName, boolean persistent, String amiId, String securityGroupName, String keyPairName, String instance_type,
			String purchasingOptio) {
		
		//check if exists a VM for the execution
		Instance i = checkIfInstanceExist("",vmName);
		boolean instanceExists = false;
		if(i.getInstanceId() != null) instanceExists = true;
		
		if(instanceExists) {
			//A vm for the execution, with requested characteristics exists so return it and use it
			if (checkInstanceCharacteristics(i, amiId, securityGroupName, keyPairName, instance_type, purchasingOptio)) return i.getInstanceId();
			else return i.getInstanceId()+"-toTerminate" ; //A vm for the execution, exists but not with requested characteristics so return it, terminate it and use a new one
		}else {
			//There is no existent vm for the execution, so create a new one
			return "";
		}
	}
	
	
	private String checkIfInstanceIsSpot(String instanceId) {

		DescribeInstancesResult describeInstancesRequest = ec2.describeInstances();
		List<Reservation> reservations = describeInstancesRequest.getReservations();
			
		for (Reservation reservation : reservations) {
		    for (Instance instance : reservation.getInstances()) {
		    	//check if there is the spot request id, if null or blank it is an on-demand instance, otherwise it is spot
		    	if(instance.getInstanceId().equals(instanceId) && instance.getSpotInstanceRequestId() != null && !instance.getSpotInstanceRequestId().equals("")) 
		    		return instance.getSpotInstanceRequestId();
		    }
		}
		
		return "";
	}
	
	protected static final String getUserData(String bucketName) {
  		String userData = "";
  		userData += "#!/bin/bash" + "\n"
  				+ "apt update" + "\n"
  				+ "apt -y install default-jre" + "\n"
  				+ "apt -y install unzip" + "\n"
  				+ "curl 'https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip' -o 'awscliv2.zip'" + "\n"  //Installing aws cli
  				+ "unzip awscliv2.zip" + "\n"
  				+ "sudo ./aws/install" + "\n"
  				+ "cd home/ubuntu" + "\n"
  				+ "aws s3 sync s3://"+bucketName+" ." + "\n"; 
  		
  		String base64UserData = null;
  		try {
  		    base64UserData = new String(Base64.getEncoder().encode(userData.getBytes("UTF-8")), "UTF-8");
  		} catch (UnsupportedEncodingException e) {
  		    e.printStackTrace();
  		}
  		return base64UserData;
	}
	
	private boolean waitUntilIsReady(String instanceId) {

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
		    System.out.println("   \u2022 The instance is running...waiting for status checks...");
			
		} catch(AmazonServiceException | WaiterTimedOutException | WaiterUnrecoverableException e) {
			System.out.println("   \u2022 An exception is occurred during istanceRunning check.");
			return false;
		}

		try{
			//Now the instance is running, so waiting for status check
			waiter.instanceStatusOk().run(paramsStatus);
		    System.out.println("   \u2022 Status checked successfully.");
			
		} catch(AmazonServiceException | WaiterTimedOutException | WaiterUnrecoverableException e) {
			System.out.println("   \u2022 An exception is occurred during status check.");
			return false;
		}
					
		return true;
	}

}
