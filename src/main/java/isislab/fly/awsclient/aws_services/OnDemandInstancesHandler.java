package isislab.fly.awsclient.aws_services;

import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.model.IamInstanceProfileSpecification;
import com.amazonaws.services.ec2.model.InstanceType;
import com.amazonaws.services.ec2.model.RunInstancesRequest;
import com.amazonaws.services.ec2.model.RunInstancesResult;

public class OnDemandInstancesHandler {
	
	private AmazonEC2 ec2;
    
    public OnDemandInstancesHandler (AmazonEC2 ec2) {
        this.ec2 = ec2;
    }
    
    public String createOnDemandInstance(String name, String amiId, String securityGroupName, String keyPairName, String instance_type, 
			String bucketName, IamInstanceProfileSpecification iamInstanceProfile ) {
		
		RunInstancesRequest runInstancesRequest = new RunInstancesRequest();

		runInstancesRequest.withImageId(amiId)
				   .withInstanceType(InstanceType.fromValue(instance_type))
				   .withMinCount(1)
				   .withMaxCount(1)
				   .withKeyName(keyPairName)
				   .withSecurityGroups(securityGroupName)
				   .withUserData(EC2Handler.getUserData(bucketName))
				   .withIamInstanceProfile(iamInstanceProfile);
		
		RunInstancesResult run_response = ec2.runInstances(runInstancesRequest);
		System.out.println("\n\u27A4 Creating and starting VM (on-demand) on AWS");
		return run_response.getReservation().getInstances().get(0).getInstanceId();
	}

}
