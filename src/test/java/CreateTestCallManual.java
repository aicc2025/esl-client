import org.freeswitch.esl.client.inbound.Client;
import org.freeswitch.esl.client.transport.message.EslMessage;
import org.freeswitch.esl.client.transport.message.EslHeaders.Name;

import java.net.InetSocketAddress;

/**
 * Simple utility to create a test call in FreeSWITCH.
 * This will generate CHANNEL_CREATE, CHANNEL_ANSWER, CHANNEL_DESTROY events.
 */
public class CreateTestCallManual {

    public static void main(String[] args) throws Exception {
        System.out.println("Creating test call in FreeSWITCH...");

        Client client = new Client();

        try {
            // Connect
            client.connect(new InetSocketAddress("127.0.0.1", 8021), "ClueCon", 10);
            System.out.println("Connected to FreeSWITCH");

            // Create a simple test call - calls echo application
            String command = "originate {ignore_early_media=true}loopback/9664 &echo";
            System.out.println("Executing: " + command);

            EslMessage response = client.sendApiCommand(command, "");

            System.out.println("Response: " + response.getHeaderValue(Name.REPLY_TEXT));
            System.out.println("Content-Type: " + response.getContentType());

            // Wait a bit to let the call complete
            Thread.sleep(3000);

            System.out.println("Test call completed!");

        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        } finally {
            client.close();
        }
    }
}
