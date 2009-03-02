package org.jgroups.tests;

import org.jgroups.*;
import org.jgroups.util.UUID;
import org.jgroups.util.Util;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

/**
 * Tests sending of unicasts to members not in the group (http://jira.jboss.com/jira/browse/JGRP-357)
 * @author Bela Ban
 * @version $Id: UnicastEnableToTest.java,v 1.9.4.2 2009/03/02 13:50:23 belaban Exp $
 */
@Test(groups=Global.STACK_DEPENDENT,sequential=true)
public class UnicastEnableToTest extends ChannelTestBase {
    private JChannel c1=null, c2=null;
    private static final String GROUP="UnicastEnableToTest";

    @BeforeMethod
    protected void setUp() throws Exception {
        c1=createChannel(true);
        c1.connect(GROUP);
    }

    @AfterMethod
    protected void tearDown() throws Exception {
        Util.close(c2, c1);
    }


    public void testUnicastMessageToUnknownMember() throws Exception {
        Address addr=UUID.randomUUID();
        System.out.println("sending message to non-existing destination " + addr);
        try {
            c1.send(new Message(addr, null, "Hello world"));
            assert false : "we should not get here; sending of message to " + addr + " should have failed";
        }
        catch(IllegalArgumentException ex) {
            System.out.println("received exception as expected");
        }
    }


    public void testUnicastMessageToExistingMember() throws Exception {
        c2=createChannel(c1);
        c2.connect(GROUP);
        assert 2 == c2.getView().size() : " view=" + c2.getView();
        MyReceiver receiver=new MyReceiver();
        c2.setReceiver(receiver);
        Address dest=c2.getAddress();
        c1.send(new Message(dest, null, "hello"));
        Util.sleep(500);
        List<Message> list=receiver.getMsgs();
        System.out.println("channel2 received the following msgs: " + list);
        assert 1 == list.size();
        receiver.reset();
    }


    public void testUnicastMessageToLeftMember() throws Exception {
        c2=createChannel(c1);
        c2.connect(GROUP);
        assert 2 == c2.getView().size() : "view=" + c2.getView();
        Address dest=c2.getAddress();
        c2.close();
        Util.sleep(100);
        try {
            c1.send(new Message(dest, null, "hello"));
            assert false : "we should not come here as message to previous member " + dest + " should throw exception";
        }
        catch(IllegalArgumentException ex) {
            System.out.println("got an exception, as expected");
        }
    }


    public void testUnicastMessageToLeftMemberWithEnableUnicastToEvent() throws Exception {
        c2=createChannel(c1);
        c2.connect(GROUP);
        assert 2 == c2.getView().size() : "view=" + c2.getView();
        Address dest=c2.getAddress();
        c2.close();
        Util.sleep(100);
        c1.down(new Event(Event.ENABLE_UNICASTS_TO, dest));
        c1.send(new Message(dest, null, "hello"));
        c1.down(new Event(Event.DISABLE_UNICASTS_TO, dest));
    }


    private static class MyReceiver extends ExtendedReceiverAdapter {
        List<Message> msgs=Collections.synchronizedList(new LinkedList<Message>());

        public void receive(Message msg) {
            msgs.add(msg);
        }

        List<Message> getMsgs() {
            return msgs;
        }

        void reset() {
            msgs.clear();
        }
    }
}
