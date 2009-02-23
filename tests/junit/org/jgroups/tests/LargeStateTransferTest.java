package org.jgroups.tests;


import org.jgroups.ChannelException;
import org.jgroups.ExtendedReceiverAdapter;
import org.jgroups.Global;
import org.jgroups.JChannel;
import org.jgroups.View;
import org.jgroups.protocols.TP;
import org.jgroups.protocols.pbcast.GMS;
import org.jgroups.stack.ProtocolStack;
import org.jgroups.util.Promise;
import org.jgroups.util.Util;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.*;

/**
 * Tests transfer of large states (http://jira.jboss.com/jira/browse/JGRP-225).
 * Note that on Mac OS, FRAG2.frag_size and max_bundling_size in the transport should be less than 16'000 due to
 * http://jira.jboss.com/jira/browse/JGRP-560. As alternative, increase the MTU of the loopback device to a value
 * greater than max_bundle_size, e.g.
 * ifconfig lo0 mtu 65000
 * @author Bela Ban
 * @version $Id: LargeStateTransferTest.java,v 1.20.4.1 2009/02/23 08:59:49 belaban Exp $
 */
@Test(groups={Global.STACK_DEPENDENT}, sequential=true)
public class LargeStateTransferTest extends ChannelTestBase {
    JChannel provider, requester;
    Promise<Integer> p=new Promise<Integer>();    
    long start, stop;
    final static int SIZE_1=100000, SIZE_2=1000000, SIZE_3=5000000, SIZE_4=10000000;


    protected boolean useBlocking() {
        return true;
    }


    @BeforeMethod
    protected void setUp() throws Exception {
        provider=createChannel(true, 2);
        modifyStack(provider);
        requester=createChannel(provider);
        setOOBPoolSize(provider, requester);
    }

    @AfterMethod
    protected void tearDown() throws Exception {
        Util.close(requester, provider);
    }


    public void testStateTransfer1() throws ChannelException {
        _testStateTransfer(SIZE_1, "testStateTransfer1");
    }

    public void testStateTransfer2() throws ChannelException {
        _testStateTransfer(SIZE_2, "testStateTransfer2");
    }

    public void testStateTransfer3() throws ChannelException {
        _testStateTransfer(SIZE_3, "testStateTransfer3");
    }

    public void testStateTransfer4() throws ChannelException {
        _testStateTransfer(SIZE_4, "testStateTransfer4");
    }



    private void _testStateTransfer(int size, String suffix) throws ChannelException {
        final String GROUP="LargeStateTransferTest-" + suffix;
        provider.setReceiver(new Provider(size));
        provider.connect(GROUP);
        p.reset();
        requester.setReceiver(new Requester(p));
        requester.connect(GROUP);
        View view=requester.getView();
        assert view.size() == 2 : "view is " + view + ", but should have 2 members";
        log("requesting state of " + size + " bytes");
        start=System.currentTimeMillis();
        boolean rc=requester.getState(provider.getAddress(), 20000);
        log.info("getState(): result=" + rc);
        Object result=p.getResult(10000);
        stop=System.currentTimeMillis();
        log("result=" + result + " bytes (in " + (stop-start) + "ms)");
        assertNotNull(result);
        assertEquals(result, new Integer(size));
    }


    private static void setOOBPoolSize(JChannel... channels) {
        for(JChannel channel: channels) {
            TP transport=channel.getProtocolStack().getTransport();
            transport.setOOBMinPoolSize(1);
            transport.setOOBMaxPoolSize(2);
        }
    }


    void log(String msg) {
        log.info(Thread.currentThread() + " -- "+ msg);
    }

    private static void modifyStack(JChannel ch) {
        ProtocolStack stack=ch.getProtocolStack();
        GMS gms=(GMS)stack.findProtocol(GMS.class);
        if(gms != null)
            gms.setLogCollectMessages(false);
    }


    private  class Provider extends ExtendedReceiverAdapter {
        byte[] state;

        public Provider(int size) {
            state=new byte[size];
        }

        public byte[] getState() {
            return state;
        }

        public void viewAccepted(View new_view) {
            log.info("[provider] new_view = " + new_view);
        }
        
        public void getState(OutputStream ostream){      
            ObjectOutputStream oos =null;
            try{
               oos=new ObjectOutputStream(ostream);
               oos.writeInt(state.length); 
               oos.write(state);
            }
            catch (IOException e){}
            finally{
               Util.close(ostream);
            }
        }
        public void setState(byte[] state) {
            throw new UnsupportedOperationException("not implemented by provider");
        }
    }


    private  class Requester extends ExtendedReceiverAdapter {
        Promise<Integer> p;

        public Requester(Promise<Integer> p) {
            this.p=p;
        }

        public void viewAccepted(View new_view) {
            log("[requester] new_view = " + new_view);
        }

        public byte[] getState() {
            throw new UnsupportedOperationException("not implemented by requester");
        }

        public void setState(byte[] state) {
            p.setResult(new Integer(state.length));
        }
        public void setState(InputStream istream) {
            ObjectInputStream ois=null;
            int size=0;
            try{
               ois= new ObjectInputStream(istream);
               size = ois.readInt();
               byte []stateReceived= new byte[size];
               ois.read(stateReceived);
            }
            catch (IOException e) {}
            finally {
               Util.close(ois);
            }
            p.setResult(new Integer(size));
        }
    }

}
