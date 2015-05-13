package senti_worker;

import com.amazonaws.AmazonClientException;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.regions.Region;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.sns.AmazonSNSClient;
import com.amazonaws.services.sns.model.PublishRequest;
import com.amazonaws.services.sns.model.PublishResult;
import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.AmazonSQSClient;
import com.amazonaws.services.sqs.model.DeleteMessageRequest;
import com.amazonaws.services.sqs.model.Message;
import com.amazonaws.services.sqs.model.ReceiveMessageRequest;
import java.io.IOException;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.ArrayList;
import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.apache.http.Consts;
import org.apache.http.HttpEntity;
import org.apache.http.NameValuePair;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicNameValuePair;

/**
 *
 * @author Piyali Mukherjee pm2678@columbia.edu This program is written as a Java servlet. It works in conjunction with the senti_analyzer web hosted program.
 * This program is run as a Worker environment in AWS EBS. It starts by establishing due credentials, and read the tweet_server queue asynchronously. On
 * receiving a tweet, it reaches out to a third party sentiment analyzer by raising an internal http request, and waits for the response. On receiving the
 * response, the program does a SNS to the senti_analyzer program, and updates the sentiment value.
 *
 */
public class senti_web_worker extends HttpServlet {

	private static final long serialVersionUID = 6249784281980102347L;
	private static AmazonSQS sqs;
	private static AmazonSNSClient sns;
	private static AmazonSNSClient debug_sns;
	private static String[] cred_component;
	private static String tweet_queue_url = null;
	String topic_arn = "arn:aws:sns:us-west-2:383748903720:sentiment_analysis";	//This is hardcoded for this program
	String debug_sns_arn = "arn:aws:sns:us-west-2:383748903720:debug_sns";
	private static int count_sent_to_sentiment;

	@Override
	public void init(ServletConfig conf) throws ServletException {
		super.init(conf);

		//We first establish our credentials and extract the sqs object handler
		String cred_string = getInitParameter("creds");	//We use this to test successful initialization
		cred_component = cred_string.split(":");

		BasicAWSCredentials basic_creds = new BasicAWSCredentials(cred_component[0], cred_component[1]);
		AWSCredentials credentials = basic_creds;

		//We first create the SQS object to read the two queues, tweet_server queues, and the the response queue
		sqs = new AmazonSQSClient(credentials);
		Region usWest2 = Region.getRegion(Regions.US_WEST_2);
		sqs.setRegion(usWest2);
		try {
			// Get the queue URL
			tweet_queue_url = sqs.getQueueUrl("tweet_queue").getQueueUrl();	//The sqs.getQueueUrl("queue_name") returns a GetQueueUrl object, so we do twice
		} catch (AmazonServiceException ase) {
			System.out.println("Error initializing tweet_queue_url in init() : Amazon Service Exception : " + ase.getErrorMessage());
			return;
		} catch (AmazonClientException ace) {
			System.out.println("Error initializing tweet_queue_url in init() : :Amazon Client Exception : " + ace.getMessage());
			return;
		}
		//Next we create the SNS topic interface to send the notification
		sns = new AmazonSNSClient(credentials);
		sns.setRegion(usWest2);
		debug_sns = new AmazonSNSClient(credentials);
		debug_sns.setRegion(usWest2);
		/*
		 Now we build the arraylist of the messages and the mechanism to purge this queue every 15 minutes
		 */
		LocalTime now = LocalTime.now(ZoneId.of("America/New_York"));
		count_sent_to_sentiment = 0;
	} //end of init()

	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		if (request.getRequestURL().toString().endsWith("/health_check")) {
			response.setStatus(200);
			response.setContentType("text/plain");
			response.getWriter().println("Alive");
		} else {
			response.setStatus(404);
			response.setContentType("text/plain");
			response.getWriter().println("Hmmm.... cant understand these URLs...");
		}
		return;
	}

	@Override
	protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		//we do a brute force message read as the AWS SDK has proven to be unreliable
		String msg_id;
		String msg_body;
		try {
			BufferedReader reader = req.getReader();
			String line;
			msg_id = reader.readLine();
			msg_body = reader.readLine();
			reader.reset();
		} catch (IOException ex) {
			return;
		}
		//debug
		//String debug_msg_0 = "Debug 0 : Received a POST\n" + msg_id + "\n" + msg_body;
		//PublishRequest debug_pub_0 = new PublishRequest(debug_sns_arn, debug_msg_0, "Debug 0 : POST rcvd " + msg_id);
		//PublishResult debug_pub_result_0 = debug_sns.publish(debug_pub_0);
		//end debug
/*
		 LocalTime now = LocalTime.now(ZoneId.of("America/New_York"));
		 ReceiveMessageRequest receiveMessageRequest = new ReceiveMessageRequest(tweet_queue_url);
		 receiveMessageRequest.setMaxNumberOfMessages(1);
		 List<Message> msgs = sqs.receiveMessage(receiveMessageRequest).getMessages();
		 if (msgs.isEmpty()) {	//Problematic situation
		 //debug
		 String debug_msg_1 = "Debug 1 : msgs list is empty\n";
		 PublishRequest debug_pub_1 = new PublishRequest(debug_sns_arn, debug_msg_1, "Debug 1 : msgs list is empty");
		 PublishResult debug_pub_result_1 = debug_sns.publish(debug_pub_1);
		 //end debug
		 resp.setContentType("text/plain");
		 resp.getWriter().println("Received 0 length msgs List<>");
		 return;
		 }
		 //debug
		 String debug_msg_1 = "Debug 1 : Received a POST from : "+req.getRequestURL().toString()+" of length : "+msgs.size()+"\n";
		 PublishRequest debug_pub_1 = new PublishRequest(debug_sns_arn, debug_msg_1, "Debug 1 : POST received with msg length : "+msgs.size());
		 PublishResult debug_pub_result_1 = debug_sns.publish(debug_pub_1);
		 //end debug

		 for (Message m : msgs) {
		 String[] body_parts = m.getBody().split("\n");
		 String msg_id = body_parts[0].trim();
		 String msg_body = body_parts[1].trim();

		 //If we have reached here, the message has not been received in past, so we start processing it.
		 //We first delete it off the queue, so that it does not find its way to another processing worker
		 String messageRecieptHandle = m.getReceiptHandle();
		 sqs.deleteMessage(new DeleteMessageRequest(tweet_queue_url, messageRecieptHandle));
		 //debug
		 String debug_msg_2 = "Debug 2 msg : \n" + msg_id + " : msg properly deleted\n" + msg_body;
		 PublishRequest debug_pub_2 = new PublishRequest(debug_sns_arn, debug_msg_2, "Debug 2 : msg "+msg_id+" removed from q");
		 PublishResult debug_pub_result_2 = debug_sns.publish(debug_pub_2);
		 //end debug
		 */			//Next we form the necessary header to send the message to sentiment analysis external url
		//	//impersonates a HTTP response for debugging purposes
		StringBuilder senti_result = new StringBuilder();
		String url = "http://debug-senti-server.elasticbeanstalk.com/debug_senti_server";//we could use alchemy if there was no throttle
		CloseableHttpClient client = HttpClients.createDefault();
		HttpPost post = new HttpPost(url);
		post.setHeader("User-agent", "Mozilla/5.0");
		List<NameValuePair> params = new ArrayList<>();
		params.add(new BasicNameValuePair("apikey", "6e3a504462d2a0f80dc4cfabe1e4e77b2e41931a"));
		params.add(new BasicNameValuePair("outputMode", "xml"));
		params.add(new BasicNameValuePair("showSourceText", "1"));
		params.add(new BasicNameValuePair("text", msg_body));
		post.setEntity(new UrlEncodedFormEntity(params, Consts.UTF_8));
		CloseableHttpResponse reply;
		reply = client.execute(post);
		int ret_status = reply.getStatusLine().getStatusCode();
		if (ret_status == 200) {
			HttpEntity reply_entity = reply.getEntity();
			InputStream in_stream = null;
			if (reply_entity != null) {
				try {
					in_stream = reply_entity.getContent();
					InputStreamReader in_stream_reader = new InputStreamReader(in_stream);
					BufferedReader reader = new BufferedReader(in_stream_reader);
					String a_line = "";
					while ((a_line = reader.readLine()) != null) {
						senti_result.append(a_line);
					}
					//debug
					//String debug_msg_2 = "Debug 2 Success returned : \n" + senti_result;
					//PublishRequest debug_pub_2 = new PublishRequest(debug_sns_arn, debug_msg_2, "Debug 2 : external website returned OK");
					//PublishResult debug_pub_result_2 = debug_sns.publish(debug_pub_2);
					//end debug
				} finally {
					in_stream.close();
				}
			}
		} else {
			//debug
			//String debug_msg_2 = "Debug 2 Failure returned : \n" + ret_status;
			//PublishRequest debug_pub_2 = new PublishRequest(debug_sns_arn, debug_msg_2, "Debug 2 : external website returned error : "+ret_status);
			//PublishResult debug_pub_result_2 = debug_sns.publish(debug_pub_2);
			//end debug
		}
		//Now we do the real processing of the web returned value
		String extract_sentiment = "";
		int loc_of_status = senti_result.indexOf("<status>");
		String actual_status = senti_result.substring(loc_of_status + 8, loc_of_status + 10);
		if (actual_status.equalsIgnoreCase("OK")) {
			int loc_of_score = senti_result.indexOf("<score>");
			extract_sentiment = senti_result.substring(loc_of_score + 7, senti_result.indexOf("</score>"));
			//String count = senti_result.substring(senti_result.indexOf("<count>") + 7, senti_result.indexOf("</count>"));
			//Now we form the message back to the HTTP endpoint
			String reply_str = msg_id + ":" + extract_sentiment;
			PublishRequest pub = new PublishRequest(topic_arn, reply_str);
			PublishResult pub_result = sns.publish(pub);
			//debug
			//String debug_msg_4 = "Debug 4 Success for msg : \n" + msg_id + " sentiment : " + extract_sentiment;
			//PublishRequest debug_pub_4 = new PublishRequest(debug_sns_arn, debug_msg_4, "Debug 4 : msg id : " + msg_id + " senti : " + extract_sentiment);
			//PublishResult debug_pub_result_4 = debug_sns.publish(debug_pub_4);
			//end debug
		} else {
			int loc_of_err_info = senti_result.indexOf("<statusInfo>");
			if (loc_of_err_info > 0) {
				String error_status = senti_result.substring(loc_of_err_info + 12, senti_result.indexOf("</statusInfo>"));
				//System.out.println("Got an ERROR :\n" + error_status);
			}
		}
		resp.setContentType("text/plain");
		resp.getWriter().println("Successfully processed ...");
	} //end of doPost

	@Override
	public void destroy() {
	}
} //end of class
