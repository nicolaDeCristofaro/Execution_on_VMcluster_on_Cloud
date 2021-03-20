package isislab.fly.awsclient.aws_services;

import java.io.File;
import java.util.List;

import com.amazonaws.AmazonClientException;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.Bucket;
import com.amazonaws.services.s3.transfer.Transfer;
import com.amazonaws.services.s3.transfer.TransferManager;

public class S3Handler {
	
	private AmazonS3 s3;
	private TransferManager tm;
	
    public S3Handler (AmazonS3 s3, TransferManager tm) {
        this.s3 = s3;
        this.tm = tm;
    }
	
	public  String createBucket(String bucketName) {
		
		System.out.println("\u27A4 S3 bucket setting up...");
		
		Bucket b = null;
		try {
		    if (s3.doesBucketExistV2(bucketName)) {
		        b = getBucket(bucketName);
		        if (b != null) {
			        System.out.format("   \u2022 Taking the bucket %s already existent.\n", bucketName);
		        }else {
			        System.out.format("   \u2022 Bucket  name %s is already used, try another name.\n", bucketName);
			        return "";
		        }
		    } else {
		        b = s3.createBucket(bucketName);
				System.out.println("   \u2022 Bucket created.");
		    }
		}catch (AmazonServiceException e) {
		    System.err.println(e.getErrorMessage());
		    System.exit(1);
		}
		
	    return b.getName();
	}
	
	private Bucket getBucket(String bucketName) {
		Bucket named_bucket = null;
        List<Bucket> buckets = s3.listBuckets();
        for (Bucket b : buckets) {
            if (b.getName().equals(bucketName)) {
                named_bucket = b;
            }
        }
        return named_bucket;
	}
	
	public void uploadProjectToExecuteOnS3(String bucketName, File zipProject) {
		
		try {
			System.out.print("   \u2022 Uploading ZIP file to AWS S3...");
			
			Transfer xfer = tm.upload(bucketName, zipProject.getName(), zipProject);
			waitForCompletion(xfer);
			
			System.out.println("Done");
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
	
	private void waitForCompletion(Transfer xfer) {
        try {
            xfer.waitForCompletion();
        } catch (AmazonServiceException e) {
            System.err.println("Amazon service error: " + e.getMessage());
            System.exit(1);
        } catch (AmazonClientException e) {
            System.err.println("Amazon client error: " + e.getMessage());
            System.exit(1);
        } catch (InterruptedException e) {
            System.err.println("Transfer interrupted: " + e.getMessage());
            System.exit(1);
        }
    }
	

}
