package isislab.fly.awsclient.aws_services;

import java.util.ArrayList;
import java.util.List;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.model.DescribeSpotInstanceRequestsRequest;
import com.amazonaws.services.ec2.model.DescribeSpotInstanceRequestsResult;
import com.amazonaws.services.ec2.model.IamInstanceProfileSpecification;
import com.amazonaws.services.ec2.model.InstanceType;
import com.amazonaws.services.ec2.model.LaunchSpecification;
import com.amazonaws.services.ec2.model.RequestSpotInstancesRequest;
import com.amazonaws.services.ec2.model.RequestSpotInstancesResult;
import com.amazonaws.services.ec2.model.SpotInstanceRequest;

public class SpotInstancesHandler {
	
	private AmazonEC2         ec2;
        
    public SpotInstancesHandler (AmazonEC2 ec2) {
        this.ec2 = ec2;
    }
    
    public String createSpotInstance(String name, String amiId, String securityGroupName, String keyPairName, String instance_type, 
			String bucketName, IamInstanceProfileSpecification iamInstanceProfile ) {
    	
    	try {
	    	// Submit the spot requests.
			final String spotRequestId = submitSpotRequest(securityGroupName, amiId, InstanceType.fromValue(instance_type),
				keyPairName, bucketName, iamInstanceProfile);
			
			// Wait until the spot request is in the active state
	        // (or at least not in the open state).
	        do
	        {
	            Thread.sleep(2000);
	        } while (isOpen(spotRequestId));
	        
	        //Loop ASYNC to check for interruption notice of spot instance
	        Thread newThread = new Thread(() -> {
	            
	            while(true) {
	                if (checkForInterruption(spotRequestId)) {
	                	System.out.println("**Spot Instance marked for termination...Your instance will be interrupted soon.");
	                	System.exit(1);
	                }else {
	                	try {
	                		//check again in 1 minute
							Thread.sleep(60000);
						} catch (InterruptedException e) {
							e.printStackTrace();
						}
	                }
	            }
	        });
	        newThread.start();
	        
			System.out.println("\n\u27A4 Creating and starting VM (spot) on AWS");
	        return getSpotInstanceId(spotRequestId);
		
	    } catch (AmazonServiceException ase) {
	        // Write out any exceptions that may have occurred.
	        System.out.println("Caught Exception: " + ase.getMessage());
	        System.out.println("Reponse Status Code: " + ase.getStatusCode());
	        System.out.println("Error Code: " + ase.getErrorCode());
	        System.out.println("Request ID: " + ase.getRequestId());
	    } catch (InterruptedException e) {
			e.printStackTrace();
		} catch (Exception e) {
			e.printStackTrace();
		}
    	
    	return "";
    }
    
	private String submitSpotRequest(String secuirtyGroupName, String amiId, InstanceType instanceType,
	    	String keyPairName, String bucketName, IamInstanceProfileSpecification iamInstanceProfile) {
	
	        // Initializes a Spot Instance Request
	        RequestSpotInstancesRequest spotRequest = new RequestSpotInstancesRequest()
	        		.withSpotPrice("0.005")
	        		.withInstanceCount(Integer.valueOf(1));
	
	        // Setup the specifications of the launch.
	        LaunchSpecification launchSpecification = new LaunchSpecification();
	        launchSpecification.setImageId(amiId);
	        launchSpecification.setInstanceType(instanceType);
	        launchSpecification.setKeyName(keyPairName);
	        launchSpecification.setUserData(EC2Handler.getUserData(bucketName));
	        launchSpecification.setIamInstanceProfile(iamInstanceProfile);
	        
	
	        // Add the security group to the request.
	        ArrayList<String> securityGroups = new ArrayList<String>();
	        securityGroups.add(secuirtyGroupName);
	        launchSpecification.setSecurityGroups(securityGroups);
	
	        // Add the launch specifications to the request.
	        spotRequest.setLaunchSpecification(launchSpecification);
	
	        // Call the RequestSpotInstance API.
	        RequestSpotInstancesResult spotResult = ec2.requestSpotInstances(spotRequest);
	        List<SpotInstanceRequest> spotResponses = spotResult.getSpotInstanceRequests();
	        
	        ArrayList<String> spotInstanceRequestIds = new ArrayList<String>();
	
	        for (SpotInstanceRequest requestResponse : spotResponses) {
	            spotInstanceRequestIds.add(requestResponse.getSpotInstanceRequestId());
	        }
	        
	        return spotInstanceRequestIds.get(0);
	    }
	
		private boolean isOpen(String spotInstanceRequestId) {
		
		    // Create the describeRequest with the request id to monitor 
		    DescribeSpotInstanceRequestsRequest describeRequest = new DescribeSpotInstanceRequestsRequest()
	    		    		.withSpotInstanceRequestIds(spotInstanceRequestId);
		
		    try
		    {
		        DescribeSpotInstanceRequestsResult describeResult = ec2.describeSpotInstanceRequests(describeRequest);
		        List<SpotInstanceRequest> describeResponses = describeResult.getSpotInstanceRequests();
		
		        // Look for the request and determine if it is in the active state.
		        for (SpotInstanceRequest describeResponse : describeResponses) {
		            if (describeResponse.getSpotInstanceRequestId().equals(spotInstanceRequestId) && 
		            describeResponse.getState().equals("open")) {
		                return true;
		            }
		        }
		    } catch (AmazonServiceException e) {
		        // Print out the error.
		        System.out.println("Error when calling describeSpotInstances");
		        System.out.println("Caught Exception: " + e.getMessage());
		        System.out.println("Reponse Status Code: " + e.getStatusCode());
		        System.out.println("Error Code: " + e.getErrorCode());
		        System.out.println("Request ID: " + e.getRequestId());
		
		        // If we have an exception, ensure we don't break out of the loop.
		        // This prevents the scenario where there was blip on the wire.
		        return true;
		    }
		
		    return false;
		}
		
		private String getSpotInstanceId(String spotInstanceRequestId) {
		
		    // Create the describeRequest with the spot request  
		    DescribeSpotInstanceRequestsRequest describeRequest = new DescribeSpotInstanceRequestsRequest()
	    		    		.withSpotInstanceRequestIds(spotInstanceRequestId);
	    		    		
	    	// Initialize variables.
			    ArrayList<String> instanceIds = new ArrayList<String>();
			
			    try
			    {
			        // Retrieve all of the requests we want to monitor.
			        DescribeSpotInstanceRequestsResult describeResult = ec2.describeSpotInstanceRequests(describeRequest);
			        List<SpotInstanceRequest> describeResponses = describeResult.getSpotInstanceRequests();
			
			        for (SpotInstanceRequest describeResponse : describeResponses) {
			        	instanceIds.add(describeResponse.getInstanceId()); 
			        }
			    } catch (AmazonServiceException e) {
			        // Print out the error.
			        System.out.println("Error when calling describeSpotInstances");
			        System.out.println("Caught Exception: " + e.getMessage());
			        System.out.println("Reponse Status Code: " + e.getStatusCode());
			        System.out.println("Error Code: " + e.getErrorCode());
			        System.out.println("Request ID: " + e.getRequestId());
			    }
			    
			    return instanceIds.get(0);
		}
	    		    		
		
		private boolean checkForInterruption(String spotInstanceRequestId) {
		
		    DescribeSpotInstanceRequestsRequest describeRequest = new DescribeSpotInstanceRequestsRequest()
	    		    		.withSpotInstanceRequestIds(spotInstanceRequestId);
		
		    try
		    {
		        DescribeSpotInstanceRequestsResult describeResult = ec2.describeSpotInstanceRequests(describeRequest);
		        List<SpotInstanceRequest> describeResponses = describeResult.getSpotInstanceRequests();
		
		        // Look for the request and determine its state.
		        for (SpotInstanceRequest describeResponse : describeResponses) {
		            if (describeResponse.getSpotInstanceRequestId().equals(spotInstanceRequestId) && 
	            		describeResponse.getStatus().getCode().equals("marked-for-termination")) return true;
		        }
		    } catch (AmazonServiceException e) {
		        // Print out the error.
		        System.out.println("Error when calling describeSpotInstances");
		        System.out.println("Caught Exception: " + e.getMessage());
		        System.out.println("Reponse Status Code: " + e.getStatusCode());
		        System.out.println("Error Code: " + e.getErrorCode());
		        System.out.println("Request ID: " + e.getRequestId());
		
		        // If we have an exception, ensure we don't break out of the loop.
		        // This prevents the scenario where there was blip on the wire.
		        return true;
		    }
		
		    return false;
		}


}
