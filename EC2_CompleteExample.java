package ec2;

import java.util.Properties;

import com.amazonaws.auth.SystemPropertiesCredentialsProvider;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.AmazonEC2ClientBuilder;
import com.amazonaws.services.simplesystemsmanagement.AWSSimpleSystemsManagement;
import com.amazonaws.services.simplesystemsmanagement.AWSSimpleSystemsManagementClientBuilder;

public class EC2_CompleteExample {

	public static void main(String[] args) {
		
		//Setting instance name and amiId
	    String name;
	    
		if (args.length != 1) {
			name = "instanceX";
	    }else name = args[0];
	
		String amiId = "ami-0817d428a6fb68645"; // Ubuntu Server 18.04 LTS 64-bit (x86)
	    
	    //Setting AWS credentials as Java System Properties - Class Credentials contains my User Creds
	    Properties p = new Properties(System.getProperties());
	    p.setProperty("aws.accessKeyId", Credentials.access_key_id);
	    p.setProperty("aws.secretKey", Credentials.secret_access_key);
	    System.setProperties(p);
	    
	    //Creation of EC2 client
        AmazonEC2 ec2 = AmazonEC2ClientBuilder.standard()
                .withRegion(Regions.US_EAST_1)
                .withCredentials(new SystemPropertiesCredentialsProvider())
                .build();
        
        //1-Create and start your instance with pre-configured script
        String instanceId = EC2_Utils.createEC2Instance(ec2, name, amiId);
                
        //Creation of AWS SSM client
        AWSSimpleSystemsManagement ssm = AWSSimpleSystemsManagementClientBuilder.standard()
        		.withRegion(Regions.US_EAST_1)
                .withCredentials(new SystemPropertiesCredentialsProvider())
                .build();
        
        //2-Run command described in the SM document
        EC2_Utils.runCommand(ssm, instanceId);
        
        //3-Terminate the instance created
        EC2_Utils.terminateInstance(ec2, instanceId);
        
	}
}
