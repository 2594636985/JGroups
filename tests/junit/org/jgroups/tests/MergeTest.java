package org.jgroups.tests;

import java.util.Properties;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import junit.framework.Test;
import junit.framework.TestSuite;

import org.jgroups.JChannel;
import org.jgroups.View;
import org.jgroups.protocols.DISCARD;
import org.jgroups.protocols.FD;
import org.jgroups.protocols.MERGE2;
import org.jgroups.protocols.MPING;
import org.jgroups.stack.Protocol;
import org.jgroups.stack.ProtocolStack;
import org.jgroups.util.Util;

/**
 * Tests merging on all stacks
 * 
 * @author vlada
 * @version $Id: MergeTest.java,v 1.12.4.1 2008/01/09 08:12:10 vlada Exp $
 */
public class MergeTest extends ChannelTestBase {
   
    public boolean useBlocking() {
        return false;
    }
   
    public void testMerging2Members() {
        String[] names = null;
        if(isMuxChannelUsed()){           
            names = createMuxApplicationNames(1, 2);            
        }else{
            names = createApplicationNames(2);            
        }
        mergeHelper(names);
    }
    
    public void testMerging4Members() {
        String[] names = null;
        if(isMuxChannelUsed()){            
            names = createMuxApplicationNames(1, 4);            
        }else{
            names = createApplicationNames(4);            
        }
        mergeHelper(names);
    }

    /**
     *
     * 
     */
    protected void mergeHelper(String [] names) {               
        int count = names.length;

        //List<MergeApplication> channels = new ArrayList<MergeApplication>();
        MergeApplication[] channels = new MergeApplication[count];
        try{
            // Create a semaphore and take all its permits
            Semaphore semaphore = new Semaphore(count);
            semaphore.acquire(count);

            // Create activation threads that will block on the semaphore
            for(int i = 0;i < count;i++){               
                channels[i] = new MergeApplication(names[i],semaphore,false);                    
                // Release one ticket at a time to allow the thread to start
                // working
                channels[i].start();
                semaphore.release(1);
                //sleep at least a second and max second and a half
                sleepRandom(1000,1500);
            }

            // Make sure everyone is in sync
            
            blockUntilViewsReceived(channels, 60000);
            

            // Sleep to ensure the threads get all the semaphore tickets
            Util.sleep(2000);
            
            int split = count/2;
            
            for (int i = 0; i < split; i++) {              
                DISCARD discard=(DISCARD)((JChannel)channels[i].getChannel()).getProtocolStack().findProtocol("DISCARD");               
                for(int j=split;j<count;j++){
                    discard.addIgnoreMember(channels[j].getLocalAddress());
                }                   
            }
            
            for (int i = count-1; i >= split; i--) {              
                DISCARD discard=(DISCARD)((JChannel)channels[i].getChannel()).getProtocolStack().findProtocol("DISCARD");               
                for(int j=0;j<split;j++){
                    discard.addIgnoreMember(channels[j].getLocalAddress());
                }                   
            }
                                        
            System.out.println("Waiting for split to be detected...");
            Util.sleep(35*1000);
            
            System.out.println("Waiting for merging to kick in....");
            
            for (int i = 0; i < count; i++) {              
                ((JChannel)channels[i].getChannel()).getProtocolStack().removeProtocol("DISCARD");                                     
            }            
                       
            //Either merge properly or time out...
            //we check that each channel again has correct view
            blockUntilViewsReceived(channels, 60000);
            

            // Re-acquire the semaphore tickets; when we have them all
            // we know the threads are done
            boolean acquired = semaphore.tryAcquire(count, 20, TimeUnit.SECONDS);
            if(!acquired){
                log.warn("Most likely a bug, analyse the stack below:");
                log.warn(Util.dumpThreads());
            }                 
            Util.sleep(1000);
        }catch(Exception ex){
            log.warn("Exception encountered during test", ex);
            fail(ex.getLocalizedMessage());
        }finally{
            
            for(MergeApplication channel:channels){
                channel.cleanup();
                Util.sleep(2000);
            }
            if(useBlocking()){
                for(MergeApplication channel:channels){                
                    checkEventStateTransferSequence(channel);
                }
            }
        }
    }   
    
    protected class MergeApplication extends PushChannelApplicationWithSemaphore {      

        public MergeApplication(String name,Semaphore semaphore,boolean useDispatcher) throws Exception{
            super(name, semaphore, useDispatcher);
            replaceDiscoveryProtocol((JChannel)channel);
            addDiscardProtocol((JChannel)channel); 
            modiftFDAndMergeSettings((JChannel)channel);
        }

        public void useChannel() throws Exception {
            channel.connect("test");           
        }  
        
        @Override
        public void viewAccepted(View new_view) {
            events.add(new_view);
            log.info("Channel " + getLocalAddress()
                      + "["
                      + getName()
                      + "] accepted view "
                      + new_view);
        }
    }
    
    
    private void addDiscardProtocol(JChannel ch) throws Exception {
        ProtocolStack stack=ch.getProtocolStack();
        Protocol transport=stack.getTransport();
        DISCARD discard=new DISCARD();
        discard.setProtocolStack(ch.getProtocolStack());
        discard.start();
        stack.insertProtocol(discard, ProtocolStack.ABOVE, transport.getName());
    }
    
    private void replaceDiscoveryProtocol(JChannel ch) throws Exception {
        ProtocolStack stack=ch.getProtocolStack();
        Protocol discovery=stack.removeProtocol("TCPPING");
        if(discovery != null){
            Protocol transport = stack.getTransport();
            MPING mping =new MPING();
            mping.setProperties(new Properties());
            mping.setProtocolStack(ch.getProtocolStack());
            mping.init();
            mping.start();
            stack.insertProtocol(mping, ProtocolStack.ABOVE, transport.getName());
            System.out.println("Replaced TCPPING with MPING. See http://wiki.jboss.org/wiki/Wiki.jsp?page=JGroupsMERGE2");            
        }        
    }

    private void modiftFDAndMergeSettings(JChannel ch) {
        ProtocolStack stack=ch.getProtocolStack();

        FD fd=(FD)stack.findProtocol("FD");
        if(fd != null) {
            fd.setMaxTries(3);
            fd.setTimeout(1000);
        }
        MERGE2 merge=(MERGE2)stack.findProtocol("MERGE2");
        if(merge != null) {
            merge.setMinInterval(5000);
            merge.setMaxInterval(10000);
        }      
    }

    public static Test suite() {
        return new TestSuite(MergeTest.class);
    }

    public static void main(String[] args) {
        String[] testCaseName = { MergeTest.class.getName() };
        junit.textui.TestRunner.main(testCaseName);
    }
}
