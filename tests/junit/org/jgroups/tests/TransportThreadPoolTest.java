package org.jgroups.tests;

import org.jgroups.JChannel;
import org.jgroups.Message;
import org.jgroups.ReceiverAdapter;
import org.jgroups.protocols.TP;
import org.jgroups.stack.ProtocolStack;
import org.jgroups.util.Util;

import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * @author Bela Ban
 * @version $Id: TransportThreadPoolTest.java,v 1.1 2007/11/21 11:26:43 belaban Exp $
 */
public class TransportThreadPoolTest extends ChannelTestBase {
    JChannel c1, c2;

    protected void setUp() throws Exception {
        super.setUp();
        c1=createChannel();
        c2=createChannel();
    }

    protected void tearDown() throws Exception {
        super.tearDown();
        c2.close();
        c1.close();
    }


    public void testThreadPoolReplacement() throws Exception {
        Receiver r1=new Receiver(), r2=new Receiver();
        c1.setReceiver(r1);
        c2.setReceiver(r2);
        c1.connect("x");
        c2.connect("x");

        c1.send(null, null, "hello world");
        c2.send(null, null, "bela");

        Util.sleep(500); // need to sleep because message sending is asynchronous
        assertEquals(2, r1.getMsgs().size());
        assertEquals(2, r2.getMsgs().size());

        TP transport=(TP)c1.getProtocolStack().getTransport();
        ExecutorService thread_pool=Executors.newCachedThreadPool();
        transport.setDefaultThreadPool(thread_pool);

        transport=(TP)c2.getProtocolStack().getTransport();
        thread_pool=Executors.newCachedThreadPool();
        transport.setDefaultThreadPool(thread_pool);

        c1.send(null, null, "message 3");
        c2.send(null, null, "message 4");
        Util.sleep(500);

        System.out.println("messages c1: " + print(r1.getMsgs()) + "\nmessages c2: " + print(r2.getMsgs()));
        assertEquals(4, r1.getMsgs().size());
        assertEquals(4, r2.getMsgs().size());
    }


    private static String print(List<Message> msgs) {
        StringBuilder sb=new StringBuilder();
        for(Message msg: msgs) {
            sb.append("\"" + msg.getObject() + "\"").append(" ");
        }
        return sb.toString();
    }


    private static class Receiver extends ReceiverAdapter {
        List<Message> msgs=new LinkedList<Message>();

        public List<Message> getMsgs() {
            return msgs;
        }

        public void receive(Message msg) {
            msgs.add(msg);
        }
    }
}
