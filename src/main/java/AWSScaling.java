import com.amazonaws.services.ec2.model.*;
import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.AmazonSQSClientBuilder;
import com.amazonaws.services.sqs.model.*;
import com.amazonaws.services.sqs.model.GetQueueAttributesRequest;
import com.amazonaws.services.ec2.model.RunInstancesResult;
import com.amazonaws.services.ec2.model.Tag;

import java.util.*;
import java.util.concurrent.TimeUnit;

import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.AmazonEC2ClientBuilder;


public class AWSScaling{
    private static final String QUEUE_NAME = "bbQueue";
    private static final int MAX_NUM_INSTANCES = 5;
    private static final String INSTANCE_NAME_PRE = "app-instance";

    private static final String IMAGE_ID = "ami-0e355297545de2f82";
    private AmazonEC2 ec2;
    private int instanceNum = 0;
    private boolean[] instanceNumLst = null;

    public AWSScaling(){

        ec2 = AmazonEC2ClientBuilder.defaultClient();
        instanceNumLst = new boolean[MAX_NUM_INSTANCES];
        for(int i = 0; i < MAX_NUM_INSTANCES; i++)
            instanceNumLst[i] = true;
    }

    private int getAvailableNum(){
        int i = 0;
        while(i< MAX_NUM_INSTANCES){
            if (instanceNumLst[i]) {
                instanceNumLst[i] = false;
                break;
            }
            else
                i++;
        }
        return i;
    }

    public void scaleApplication() {

        int queueSize = getSQSLength();
        int requiredNum = Math.min(queueSize, MAX_NUM_INSTANCES);

        List<Instance> instances = this.getAllInstances();
        int runningNum = 0;
        LinkedList<Instance> stoppedInstance = new LinkedList<Instance>();
        for(Instance i: instances){
            int instanceNo = Integer.parseInt(i.getTags().get(0).getValue().substring(12));
            instanceNumLst[instanceNo] = false;
            String stateName = i.getState().getName().toLowerCase();
            if(stateName.equals( "running")) {
                runningNum++;

            }
            else if(stateName.equals("stopping")|| stateName.equals("stopped"))
                stoppedInstance.add(i);
        }

        if(runningNum < requiredNum){
            int addNum = requiredNum - runningNum;
            while(stoppedInstance.size()>0) {
                Instance i = stoppedInstance.get(0);
                stoppedInstance.removeFirst();
                if(i.getState().getName().toLowerCase().equals("stopping")) {
                    String status = "stopping";
                    do {
                        try {
                            TimeUnit.MILLISECONDS.sleep(100);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }

                        DescribeInstancesRequest desRequest = new DescribeInstancesRequest().withInstanceIds(i.getInstanceId());
                        DescribeInstancesResult res = ec2.describeInstances(desRequest);

                        status = res.getReservations().get(0).getInstances().get(0).getState().getName().toLowerCase();
                    }while (status.equals("stopping"));
                }

                StartInstancesRequest request = new StartInstancesRequest().withInstanceIds(i.getInstanceId());
                ec2.startInstances(request);
                Log.Log("Restarting Instance " + i.getTags().get(0).getValue());
                addNum--;
                if(addNum <= 0)
                    break;
            }

            while(addNum > 0){
                RunInstancesRequest rir = new RunInstancesRequest(IMAGE_ID,
                        1, 1);
                rir.setInstanceType("t2.micro");
                RunInstancesResult response = ec2.runInstances(rir);

                String instance_id = response.getReservation().getInstances().get(0).getInstanceId();

                List<String> idLst = new LinkedList<String>();
                idLst.add(instance_id);
                List<Tag> tagLst = new LinkedList<Tag>();

                Tag tag = new Tag("Name", INSTANCE_NAME_PRE+getAvailableNum());
                tagLst.add(tag);

                CreateTagsRequest tag_request = new CreateTagsRequest();
                tag_request.setTags(tagLst);
                tag_request.setResources(idLst);

                try {
                    ec2.createTags(tag_request);

                }
                catch (Exception e) {
                    e.printStackTrace();
                }

                addNum--;
                Log.Log("Starting Instance");
            }

            if(stoppedInstance.size() > 0){
                for(Instance ins: stoppedInstance){
                    int instanceNo = Integer.parseInt(ins.getTags().get(0).getValue().substring(12));
                    TerminateInstancesRequest request = new TerminateInstancesRequest().
                            withInstanceIds(ins.getInstanceId());//terminate instance using the instance id
                    ec2.terminateInstances(request);
                    instanceNumLst[instanceNo] = true;
                }

            }
        }

    }


    public void sendMsg() {
        final AmazonSQS sqs = AmazonSQSClientBuilder.defaultClient();
        String queueUrl = sqs.getQueueUrl(QUEUE_NAME).getQueueUrl();
        SendMessageRequest send_msg_request = new SendMessageRequest()
                .withQueueUrl(queueUrl)
                .withMessageBody("hello world");
        sqs.sendMessage(send_msg_request);
        System.out.println("send a msg");
    }


    public int getSQSLength(){
        final AmazonSQS sqs = AmazonSQSClientBuilder.defaultClient();
        GetQueueAttributesRequest request = new GetQueueAttributesRequest().withAttributeNames("ApproximateNumberOfMessages").withQueueUrl(QUEUE_NAME);
        GetQueueAttributesResult res = sqs.getQueueAttributes(request);
        Map<String,String> res_s = res.getAttributes();
        return Integer.parseInt(res_s.get("ApproximateNumberOfMessages"));

    }

    public List<Instance> getAllInstances(){

        AmazonEC2 ec2 = AmazonEC2ClientBuilder.defaultClient();

        List<Instance> instances = new ArrayList<Instance>();

        DescribeInstancesRequest request = new DescribeInstancesRequest();
        while (true) {

            DescribeInstancesResult response = ec2.describeInstances(request);
            for (Reservation rev: response.getReservations()) {
                instances.addAll(rev.getInstances());
            }

            request.setNextToken(response.getNextToken());
            if (response.getNextToken() == null)
                break;

        }

        return instances;
    }

    public static void main(String[] args)
    {
        AWSScaling sqsExample = new AWSScaling();

//        for (int i = 0; i < 10; i++)
//            sqsExample.sendMsg();
        sqsExample.scaleApplication();



       

    }
}

