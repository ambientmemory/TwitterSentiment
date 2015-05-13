"use strict";
console.log("\nHello World !! final version ... ");
process.title = 'senti-analyzer';	// this name appears in 'ps' command
//Declarations for the http servers
var http_req=require('request');
// Port where we'll run the server
var wss_port = 2678;	//my uni
var http = require('http'),fs = require('fs'), qs = require('querystring'),pt = require('periodic-task');
//Declarations for the AWS
var AWS=require('aws-sdk');

AWS.config.apiVersions = {
	ec2: 'latest',
	sqs: 'latest',
	sns: 'latest'
};
AWS.config.sqs = {region: 'us-west-2'};
AWS.config.sns = {region: 'us-west-2'};
var sqs = new AWS.SQS();
var sns = new AWS.SNS();
var sqs_params = {QueueName: 'tweet_queue'};
var queue_url;
console.log("Complete Server started at "+(new Date()));
sqs.getQueueUrl(sqs_params, function(err,data) {
	if (err) console.log(err,err.stack);
	else {
		queue_url = data.QueueUrl;
		//console.log("Debug: successful init of the queue : "+ queue_url);
	}
	return;
});
//Declarations for the Tweeter part
var twitter = require('twit');
var client= new twitter({'consumer_key' : process.env.TWITTER_CONSUMER_KEY, //KEY
						'consumer_secret': process.env.TWITTER_CONSUMER_SECRET,
						'access_token' : process.env.TWITTER_ACCESS_TOKEN_KEY,
						'access_token_secret' : process.env.TWITTER_ACCESS_TOKEN_SECRET});
//We read the twitter stream here, and store the data into an object
var universe=["-180.0","-90.0","180.0","90.0"];
var twit_stream = client.stream('statuses/filter',{locations: universe});		//We use this to test if the stream is already opened
var tweet_array = [];
//The call back functions for the twitter stream
var twit_err = function(err) {
	console.log("Debug tweet error : ",err);
	return;
};
var control_flow = 0;
var twit_succ = function(tweet) {
	if (tweet.geo === null) return;			//We ignore the tweets that come with null geo field value
	if (++control_flow !== 2) return;		//pick up only every second message - this floods the system
	control_flow = 0;
	//console.log("Debug tweet received...\nid : ",tweet.id_str+"\ntext: "+tweet.text+"\n"+JSON.stringify(tweet));
	var record = {
		"msg_id": tweet.id_str,
		"time_stamp": new Date(),
		"text": tweet.text,
		"lat" : tweet.geo.coordinates[0],
		"lng" : tweet.geo.coordinates[1],
		"senti": -2.0						//default - senti not populated
	};
	tweet_array.push(record);
	//Also send the record to the SQS message queue. Here we send the message as a modified string
	var msg_for_sqs = record.msg_id+"\n"+record.text;
	var msg_params = {
		MessageBody: msg_for_sqs,
		QueueUrl: queue_url,
		DelaySeconds: 0
	};
	sqs.sendMessage(msg_params,function(err,data) {
		if (err) console.log("Debug : SQS sending error",err,err.stack);
		//else console.log("Debug : msg sent to q : "+record.msg_id+" lng : "+record.lng+" lat: "+record.lat+" senti: "+record.senti+" time_stamp: "+record.time_stamp);
		});
	return;
}; //end of twit_succ
twit_stream.on('tweet',twit_succ);
twit_stream.on('error',twit_err);
//Cleanse old tweets, and establish a cron job to clean all old tweets
var task = new pt(86400000, function () {
	//identify all records in tweet_array and delete them - this will prevent memory bloating by the server
	if (tweet_array.length === 0) return;
	var i = 0;
	do {
		if (((new Date()) - tweet_array[i].time_stamp) > 86400000) {
			tweet_array.splice(i,1);
		}
		else {
			i++;
		}
	} while (i < tweet_array.length);
});
task.run();	//run every 24 hrs...

var tags = [];
var age = 24;		//default
var event_stream_set = false;
var update_done = false;	//This flag helps the update_frontend to decide if a new data needs to be sent or not.
//HTTP server
var http_server = http.createServer(function(request, response) {
    //console.log("Debug : HTTP server called asking URL " + request.url + " at : "+(new Date()));
	if (request.method === "GET") {
		if (request.url === '/events') {
			//console.log("GET /events called ..."+request.headers.accept);
			if (request.headers.accept.indexOf('text/event-stream') !== -1 ) {	//contains 'text/event_stream'
				//console.log("Event stream flag set ...");
				if (event_stream_set === true) {
					update_frontend(response);
					return;		//we have already set the repetitive timer, so we avoid duplicating it...
				}
				//Now we sleep for 2 seconds
				setInterval(function() {update_frontend(response);},1000);		//update every seconds
				update_frontend(response);
				event_stream_set = true;
			} else {
				response.writeHead(404, {'Content-Type': 'text/plain'});
				response.end("Sorry... content type unknown");
			}
		} else	if (request.url === '/') {		//asking for index.html
			var file_content = fs.readFileSync("index.html");
				response.writeHead(200, {'Content-Type': 'text/html'});
				response.end(file_content);
		} else if (request.url === '/tweet_server') {		//this is used for debugging only..
			response.writeHead(200, {'Content-Type': 'text/plain'});
			var count = 0;
			for (var i = 0; i < tweet_array.length; i++) {
				if (tweet_array[i].senti < -1.0 ) count++;
			}
			var result = "Received request at "+(new Date())+request.url+"\n"+"length of tweet_array : "+ tweet_array.length+" and with no senti value : "+count+"\n";
			response.end(result);
		}  else if (request.url === '/health_check') {
			response.writeHead(200, {'Content-Type': 'text/plain'});
			response.end('Alive');
		}  
		else {
			response.writeHead(404, {'Content-Type': 'text/plain'});
			response.end('Sorry, unknown GET url');
		}
	} else if (request.method === "POST") {
		if (request.url === '/data') {	//This is a request from the frontend to set up the tracking tags
			var body = '';
			request.on('data', function (data) {
				body += data;
				if (body.length > 1e6) {request.connection.destroy();};
			});
			request.on('end', function () {
				var tag_string = qs.parse(body);
				if (tag_string) {
					tags=[];
					tags.push(tag_string.tag1);
					tags.push(tag_string.tag2);
					tags.push(tag_string.tag3);
					tags.push(tag_string.tag4);
					tags.push(tag_string.tag5);
					age = tag_string.chosen_time;
				};
				if ((tags[0] === null) && (tags[1] === null) && (tags[2] === null) && (tags[3] === null) && (tags[4] === null)) return;
				console.log("BIG DEBUG : tag data recvd : ",tags);
				//Transfer the entire data set of the matching records
				var reply_str = '';
				for (var i = 0; i < tweet_array.length; i++) {
					//var senti_random_val = Math.random();
					//var sign = Math.random();
					//if (sign < 0.5) senti_random_val = (-1.0)*senti_random_val;
					//var debug_count = 0;
					//tweet_array[i].senti = senti_random_val;		//pure debugging
					for (var j =  0; j < tags.length; j++) {
						if ((tags[j] !== null) && (tags[j] !== '')) {
							if ((tweet_array[i].text.indexOf(tags[j]) !== -1) && (tweet_array[i].senti >= -1.0) && (tweet_array[i].senti <= 1.0)) {
								//Our message structure is a very large data set of type tag+";"+lat+";"+lng+";"+senti"+:"
								reply_str += tags[j]+";"+tweet_array[i].lat+";"+tweet_array[i].lng+";"+tweet_array[i].senti+":";
								update_done = true;
							}
						}
					}
				}
				//if (reply_str){console.log("Returning data : "+debug_count+"records...");};
				response.writeHead(200, {'Content-Type': 'text/plain'});
				response.end(reply_str);
			});
		} else if (request.url === "/") {
			var hdr = request.headers;
			var header_array = request.rawHeaders;
			var message_type = header_array[1];		//The second element
			var last_element = header_array[header_array.length-1];
			if (message_type === "SubscriptionConfirmation") {
				var sns_note = "";
				request.on('data',function(data) { sns_note += data;});
				request.on('end',function() {
					//given that the usual JSON library does not work, we do a string extraction of the url
					var start = sns_note.indexOf("https:");
					var extr = sns_note.substring(start);
					var end = extr.indexOf(",");
					end--;
					var confirm_url = extr.substring(0,end);
					//console.log("Conf url appears to be : ",confirm_url);
					http_req(confirm_url,function(err,resp,body){});
					//console.log("confirmation sent...");
				});
			} else	if (message_type === "Notification") {
				var sns_note = "";
				request.on('data',function(data) {
					sns_note += data;
				});
				request.on('end',function() {		
					//console.log("POST : notification rcvd "+sns_note);
					var start = sns_note.indexOf("MessageId");
					var extr = sns_note.substring(start+15);
					start = extr.indexOf("Message");
					var extr1 = extr.substring(start+9);
					//console.log("extr1 : "+extr1);
					start = extr1.indexOf('"')+1;
					var end = extr1.indexOf(",")-1;
					var senti = extr1.substring(start,end).trim();
					//console.log("msg as received : "+senti);
					//Our message is a colon delimited msg_id:sentiment value
					var tokens = senti.split(":"); //token[0]=msg_id
					//console.log("msg as tokens : "+tokens[0]+" and : "+tokens[1]);
					//We locate the message with this message id, and update the tweet_array
					for (var i = 0; i < tweet_array.length; i++) {
						if (tweet_array[i].msg_id === tokens[0]) {
							tweet_array[i].senti = tokens[1];
							update_done = true;
						}
					}
				}); //end of request.on
			} //end of notification
		}
	} else {
			response.writeHead(404, {'Content-Type': 'text/plain'});
			response.end('Sorry, unknown message type');			
			console.log("received unknown or no message type : "+message_type);
		}
});
http_server.listen(wss_port, function() {console.log("Server is listening on port " + wss_port+" at : "+(new Date()));});

function update_frontend(response) {
	if (update_done === false ) return;	//No fresh update of the tweet_array has happened
	if ((tags[0] === null) && (tags[1] === null) && (tags[2] === null) && (tags[3] === null) && (tags[4] === null)) return;
	response.writeHead(200,{
		'Content-Type': 'text/event-stream',
		'Cache-Control': 'no-cache',
		'Connection': 'keep-alive'
	});
	var reply_str = '';
	var debug_count = 0;
	for (var i = 0; i < tweet_array.length; i++) {
		for (var j =  0; j < tags.length; j++) {
			if ((tags[j] !== null) && (tags[j] !== '')) {
				if ((tweet_array[i].text.indexOf(tags[j]) !== -1) && (tweet_array[i].senti >= -1.0) && (tweet_array[i].senti <= 1.0)) {
				//Our message structure is a very large data set of type tag+";"+lat+";"+lng+";"+senti"+:"
					reply_str += tags[j]+";"+tweet_array[i].lat+";"+tweet_array[i].lng+";"+tweet_array[i].senti+":";
					debug_count++;
				}
			}
		}
	}
	response.write("data:" + reply_str + '\n\n');
	//response.end();		//This causes stream error, as SSE is just one long connection, so should not be CLOSED ..
	update_done = false;	//set as processed
}