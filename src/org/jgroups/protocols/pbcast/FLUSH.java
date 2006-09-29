package org.jgroups.protocols.pbcast;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.Vector;

import org.jgroups.Address;
import org.jgroups.Event;
import org.jgroups.Header;
import org.jgroups.Message;
import org.jgroups.TimeoutException;
import org.jgroups.View;
import org.jgroups.ViewId;
import org.jgroups.stack.Protocol;
import org.jgroups.util.Promise;
import org.jgroups.util.Streamable;
import org.jgroups.util.Util;

/**
 * Flush, as it name implies, forces group members to flush their pending messages 
 * while blocking them to send any additional messages. The process of flushing 
 * acquiesces the group so that state transfer or a join can be done. It is also 
 * called stop-the-world model as nobody will be able to send messages while a 
 * flush is in process.
 * 
 * <p>
 * Flush is needed for:
 * <p>
 * (1) 	State transfer. When a member requests state transfer, the coordinator 
 * 		tells everyone to stop sending messages and waits for everyone's ack. Then it asks
 *  	the application for its state and ships it back to the requester. After the 
 *  	requester has received and set the state successfully, the coordinator tells 
 *  	everyone to resume sending messages.
 * <p>  
 * (2) 	View changes (e.g.a join). Before installing a new view V2, flushing would 
 * 		ensure that all messages *sent* in the current view V1 are indeed *delivered* 
 * 		in V1, rather than in V2 (in all non-faulty members). This is essentially 
 * 		Virtual Synchrony.
 * 
 * 
 * 
 * @author Vladimir Blagojevic
 * @version $Id$
 * @since 2.4
 */
public class FLUSH extends Protocol
{
   public static final String NAME = "FLUSH";

   private View currentView;

   private Address localAddress;

   private Address flushCaller;

   private Collection flushMembers;

   private Set flushOkSet;

   private Set flushCompletedSet;

   private final Object sharedLock = new Object();

   private final Object blockMutex = new Object();

   private boolean isBlockState = true;

   private long timeout = 4000;
   
   private long block_timeout = 10000;

   private Set suspected;

   private volatile boolean receivedFirstView = false;
   
   private volatile boolean receivedMoreThanOneView = false;
   
   private volatile boolean sentBlockEventUp = false;

   private long startFlushTime;

   private long totalTimeInFlush;

   private int numberOfFlushes;

   private double averageFlushDuration;

   private Promise flush_promise;
   
   private Promise blockok_promise;
   
   private boolean auto_flush_conf = true;

   public FLUSH()
   {
      super();
      //tmp view to avoid NPE
      currentView = new View(null, new Vector());
      flushOkSet = new TreeSet();
      flushCompletedSet = new TreeSet();
      flushMembers = new ArrayList();
      suspected = new TreeSet();
      flush_promise = new Promise();
      blockok_promise = new Promise();
   }

   public String getName()
   {
      return NAME;
   }

   public boolean setProperties(Properties props)
   {      
      super.setProperties(props);
      timeout = Util.parseLong(props, "timeout", timeout);
      block_timeout = Util.parseLong(props, "block_timeout", block_timeout);
      auto_flush_conf = Util.parseBoolean(props, "auto_flush_conf", auto_flush_conf);
      
      if (props.size() > 0)
      {
         log.error("the following properties are not recognized: " + props);
         return false;
      }
      return true;
   }
   
   public void init() throws Exception
   {
      if(auto_flush_conf)
      {
         Map map = new HashMap();
         map.put("flush_timeout", new Long(timeout));
         passUp(new Event(Event.CONFIG, map));
         passDown(new Event(Event.CONFIG, map));
      }
   }

   public void start() throws Exception
   {
      Map map = new HashMap();
      map.put("flush_supported", Boolean.TRUE);
      passUp(new Event(Event.CONFIG, map));
   }

   /* -------------------JMX attributes and operations --------------------- */

   public double getAverageFlushDuration()
   {
      return averageFlushDuration;
   }

   public long getTotalTimeInFlush()
   {
      return totalTimeInFlush;
   }

   public int getNumberOfFlushes()
   {
      return numberOfFlushes;
   }

   public boolean startFlush(long timeout)
   {
      boolean successfulFlush = false;
      down(new Event(Event.SUSPEND));
      flush_promise.reset();
      try
      {
         flush_promise.getResultWithTimeout(timeout);
         successfulFlush = true;
      }
      catch (TimeoutException e)
      {
      }
      return successfulFlush;
   }

   public void stopFlush()
   {
      down(new Event(Event.RESUME));
   }

   /* ------------------- end JMX attributes and operations --------------------- */

   public void down(Event evt)
   {
      switch (evt.getType())
      {
         case Event.MSG :
            boolean shouldSuspendByItself = false;
            synchronized (blockMutex)
            {
               while (isFlushRunning())
               {
                  if (log.isDebugEnabled())
                     log.debug("FLUSH block at " + localAddress + " for " + timeout);
                  try
                  {
                     blockMutex.wait(timeout);
                     if (isFlushRunning())
                     {                        
                        isBlockState = false;
                        shouldSuspendByItself = true;
                     }
                  }
                  catch (InterruptedException e)
                  {
                  }
               }
            }
            if(shouldSuspendByItself)
            {
               log.warn("Forcing FLUSH unblock at " + localAddress);
               passDown(new Event(Event.SUSPEND_OK));
            }
            break;

         case Event.SUSPEND :
            onSuspend((View) evt.getArg());
            return;

         case Event.RESUME :
            onResume();
            return;
            
         case Event.BLOCK_OK:
            blockok_promise.setResult(Boolean.TRUE);
            return;
      }
      passDown(evt);
   }

   public void up(Event evt)
   {

      Message msg = null;
      switch (evt.getType())
      {
         case Event.MSG :
            msg = (Message) evt.getArg();
            FlushHeader fh = (FlushHeader) msg.removeHeader(getName());
            if (fh != null)
            {
               if (fh.type == FlushHeader.START_FLUSH)
               {
                  boolean successfulBlock = sendBlockUpToChannel(block_timeout);
                  if (successfulBlock && log.isDebugEnabled())
                  {
                     log.debug("Blocking of channel " + localAddress + " completed successfully");
                  }                  
                  onStartFlush(msg.getSrc(), fh);
               }           
               else if (isCurrentFlushMessage(fh))
               {                                                 
				  if (fh.type == FlushHeader.STOP_FLUSH && !sentBlockEventUp)
			      {                  
                     sentBlockEventUp = true;
			         onStopFlush();
			         passUp(new Event(Event.UNBLOCK));                       
			         if (log.isDebugEnabled())
			             log.debug("Unblocked " + localAddress + " fh " + fh + " cv " + currentViewId());
			      }
                  if (fh.type == FlushHeader.FLUSH_OK)
                  {
                     onFlushOk(msg.getSrc(), fh.viewID);
                  }
                  else if (fh.type == FlushHeader.FLUSH_COMPLETED)
                  {
                     onFlushCompleted(msg.getSrc());
                  }
               }
               else
               {
                  if (log.isDebugEnabled())
                     log.debug(localAddress + " received outdated FLUSH message " + fh + ",ignoring it.");
               }
               return; //do not pass FLUSH msg up
            }
            break;

         case Event.VIEW_CHANGE :
            onViewChange((View) evt.getArg());
            passUp(evt);
            boolean firstView = receivedFirstView && !receivedMoreThanOneView;
            if (firstView)
            {
               //fake STOP_FLUSH so new member is guaranteed 
               //to receive sequence of events (View,UnblockEvent) 
               sentBlockEventUp = true;
               onStopFlush();
               passUp(new Event(Event.UNBLOCK));
               
               if (log.isDebugEnabled())
                  log.debug("Unblocked special " + localAddress);
            }
            return;

         case Event.SET_LOCAL_ADDRESS :
            localAddress = (Address) evt.getArg();
            break;

         case Event.SUSPECT :
            onSuspect((Address) evt.getArg());
            break;

         case Event.SUSPEND :
            onSuspend((View) evt.getArg());
            return;

         case Event.RESUME :
            onResume();
            return;
      }

      passUp(evt);
   }

   public Vector providedDownServices()
   {
      Vector retval = new Vector(2);
      retval.addElement(new Integer(Event.SUSPEND));
      retval.addElement(new Integer(Event.RESUME));
      return retval;
   }
   
   private boolean sendBlockUpToChannel(long btimeout)
   {
      boolean successfulBlock = false;
      blockok_promise.reset();
      
      new Thread(Util.getGlobalThreadGroup(), new Runnable()
      {
         public void run()
         {
            passUp(new Event(Event.BLOCK));
         }
      }, "FLUSH block").start();
      
      try
      {
         blockok_promise.getResultWithTimeout(btimeout);
         successfulBlock = true;
      }
      catch (TimeoutException e)
      {
         log.warn("Blocking of channel using BLOCK event timed out after " + btimeout + " msec.");
      }
      return successfulBlock;
   }

   private boolean isCurrentFlushMessage(FlushHeader fh)
   {
      return fh.viewID == currentViewId();
   }

   private long currentViewId()
   {
      long viewId = -1;
      synchronized (sharedLock)
      {
         ViewId view = currentView.getVid();
         if (view != null)
         {
            viewId = view.getId();
         }
      }
      return viewId;
   }

   private void onViewChange(View view)
   {
      boolean amINewCoordinator = false;
      synchronized (blockMutex)
      {
    	 if (receivedFirstView)
    	 {
    		 receivedMoreThanOneView = true;
    	 }
         if (!receivedFirstView)
         {
            isBlockState = false;
            blockMutex.notifyAll();
            receivedFirstView = true;
         }
      }
      synchronized (sharedLock)
      {
         suspected.retainAll(view.getMembers());
         currentView = view;         
         amINewCoordinator = flushCaller != null && !view.getMembers().contains(flushCaller)
               && localAddress.equals(view.getMembers().get(0));
      }
      
      //If coordinator leaves, its STOP FLUSH message will be discarded by
      //other members at NAKACK layer. Remaining members will be hung, waiting
      //for STOP_FLUSH message. If I am new coordinator I will complete the
      //FLUSH and send STOP_FLUSH on flush callers behalf.
      if (amINewCoordinator)
      {
         if (log.isDebugEnabled())
            log.debug("Coordinator left, " + localAddress + " will complete flush");
         onResume();
      }
      
      if (log.isDebugEnabled())
         log.debug("Installing view at  " + localAddress + " view is " + currentView);
   }

   private void onStopFlush()
   {
      if (stats)
      {
         long stopFlushTime = System.currentTimeMillis();
         totalTimeInFlush += (stopFlushTime - startFlushTime);
         if (numberOfFlushes > 0)
         {
            averageFlushDuration = totalTimeInFlush / numberOfFlushes;
         }
      }
      if (log.isDebugEnabled())
         log.debug("Received STOP_FLUSH at " + localAddress);      

      synchronized (sharedLock)
      {
         flushCompletedSet.clear();
         flushOkSet.clear();
         flushMembers.clear();
         flushCaller = null;
      }              
      synchronized (blockMutex)
      {
         isBlockState = false;
         blockMutex.notifyAll();
      }           
   }

   private void onSuspend(View view)
   {
      Message msg = null;
      Collection participantsInFlush = null;
      synchronized (sharedLock)
      {
         //initiate FLUSH only on group members that we need to flush
         if (view != null)
         {
            participantsInFlush = new ArrayList(view.getMembers());
            participantsInFlush.retainAll(currentView.getMembers());
         }
         else
         {
            participantsInFlush = new ArrayList(currentView.getMembers());
         }
         msg = new Message(null, localAddress, null);
         msg.putHeader(getName(), new FlushHeader(FlushHeader.START_FLUSH, currentViewId(), participantsInFlush));
      }
      if (participantsInFlush.isEmpty())
      {
         passDown(new Event(Event.SUSPEND_OK));
      }
      else
      {
         passDown(new Event(Event.MSG, msg));
         if (log.isDebugEnabled())
            log.debug("Received SUSPEND at " + localAddress + ", sent START_FLUSH to " + participantsInFlush);
      }
   }

   private void onResume()
   {
	  long viewID = currentViewId();	
      Message msg = new Message(null, localAddress, null);
      msg.putHeader(getName(), new FlushHeader(FlushHeader.STOP_FLUSH,viewID));
      passDown(new Event(Event.MSG, msg));
      if (log.isDebugEnabled())
         log.debug("Received RESUME at " + localAddress + ", sent STOP_FLUSH to all");
   }

   private void onStartFlush(Address flushStarter, FlushHeader fh)
   {
      if (stats)
      {
         startFlushTime = System.currentTimeMillis();
         numberOfFlushes += 1;
      }
      synchronized (blockMutex)
      {
         isBlockState = true;
      }
      synchronized (sharedLock)
      {
         sentBlockEventUp = false;
         flushCaller = flushStarter;
         flushMembers.clear();
         if(fh.flushParticipants!=null)
         {
            flushMembers.addAll(fh.flushParticipants);
         }        
         flushMembers.removeAll(suspected);
      }
      Message msg = new Message(null, localAddress, null);
      msg.putHeader(getName(), new FlushHeader(FlushHeader.FLUSH_OK, fh.viewID));
      passDown(new Event(Event.MSG, msg));
      if (log.isDebugEnabled())
         log.debug("Received START_FLUSH at " + localAddress + " responded with FLUSH_OK");
   }

   private void onFlushOk(Address address, long viewID)
   {

      boolean flushOkCompleted = false;
      Message m = null;
      synchronized (sharedLock)
      {
         flushOkSet.add(address);
         flushOkCompleted = flushOkSet.containsAll(flushMembers);
         if (flushOkCompleted)
         {
            m = new Message(flushCaller, localAddress, null);
         }
      }

      if (log.isDebugEnabled())
         log.debug("At " + localAddress + "FLUSH_OK from " + address + ",completed " 
               + flushOkCompleted + ",  flushOkSet " + flushOkSet.toString());

      if (flushOkCompleted)
      {
         m.putHeader(getName(), new FlushHeader(FlushHeader.FLUSH_COMPLETED, viewID));
         passDown(new Event(Event.MSG, m));
         if (log.isDebugEnabled())
            log.debug(localAddress + " sent FLUSH_COMPLETED message to " + flushCaller);
      }
   }

   private void onFlushCompleted(Address address)
   {
      boolean flushCompleted = false;
      synchronized (sharedLock)
      {
         flushCompletedSet.add(address);
         flushCompleted = flushCompletedSet.containsAll(flushMembers);
      }

      if (log.isDebugEnabled())
         log.debug("At " + localAddress + " FLUSH_COMPLETED from " + address 
               + ",completed " + flushCompleted + ",flushCompleted "
               + flushCompletedSet.toString());

      if (flushCompleted)
      {
         //needed for jmx startFlush(timoue);
         flush_promise.setResult(Boolean.TRUE);
         passUp(new Event(Event.SUSPEND_OK));
         passDown(new Event(Event.SUSPEND_OK));
         if (log.isDebugEnabled())
            log.debug("All FLUSH_COMPLETED received at " + localAddress + " sent SUSPEND_OK down/up");
      }
   }

   private void onSuspect(Address address)
   {
      boolean flushOkCompleted = false;
      Message m = null;
      long viewID = 0;
      synchronized (sharedLock)
      {
         suspected.add(address);
         flushMembers.removeAll(suspected);
         viewID = currentViewId();
         flushOkCompleted = !flushOkSet.isEmpty() && flushOkSet.containsAll(flushMembers);
         if (flushOkCompleted)
         {
            m = new Message(flushCaller, localAddress, null);
         }
      }
      if (flushOkCompleted)
      {
         m.putHeader(getName(), new FlushHeader(FlushHeader.FLUSH_COMPLETED, viewID));
         passDown(new Event(Event.MSG, m));
         if (log.isDebugEnabled())
            log.debug(localAddress + " sent FLUSH_COMPLETED message to " + flushCaller);
      }
      if (log.isDebugEnabled())
         log.debug("Suspect is " + address + ",completed " + flushOkCompleted + ",  flushOkSet " + flushOkSet
               + " flushMembers " + flushMembers);
   }

   private boolean isFlushRunning()
   {
      return isBlockState;
   }

   public static class FlushHeader extends Header implements Streamable
   {
      public static final byte START_FLUSH = 0;

      public static final byte FLUSH_OK = 1;

      public static final byte STOP_FLUSH = 2;

      public static final byte FLUSH_COMPLETED = 3;

      byte type;

      long viewID;

      Collection flushParticipants;

      public FlushHeader()
      {
         this(START_FLUSH,0);
      } // used for externalization
     
      public FlushHeader(byte type, long viewID)
      {
         this(type, viewID, null);
      }

      public FlushHeader(byte type, long viewID, Collection flushView)
      {
         this.type = type;
         this.viewID = viewID;
         this.flushParticipants = flushView;
      }

      public String toString()
      {
         switch (type)
         {
            case START_FLUSH :
               return "FLUSH[type=START_FLUSH,viewId=" + viewID + ",members=" + flushParticipants + "]";
            case FLUSH_OK :
               return "FLUSH[type=FLUSH_OK,viewId=" + viewID + "]";
            case STOP_FLUSH :
               return "FLUSH[type=STOP_FLUSH,viewId=" + viewID + "]";
            case FLUSH_COMPLETED :
               return "FLUSH[type=FLUSH_COMPLETED,viewId=" + viewID + "]";
            default :
               return "[FLUSH: unknown type (" + type + ")]";
         }
      }

      public void writeExternal(ObjectOutput out) throws IOException
      {
         out.writeByte(type);
         out.writeLong(viewID);
         out.writeObject(flushParticipants);
      }

      public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException
      {
         type = in.readByte();
         viewID = in.readLong();
         flushParticipants = (Collection) in.readObject();
      }

      public void writeTo(DataOutputStream out) throws IOException
      {
         out.writeByte(type);
         out.writeLong(viewID);
         if (flushParticipants != null && !flushParticipants.isEmpty())
         {
            out.writeShort(flushParticipants.size());
            for (Iterator iter = flushParticipants.iterator(); iter.hasNext();)
            {
               Address address = (Address) iter.next();
               Util.writeAddress(address, out);
            }
         }
         else
         {
            out.writeShort(0);
         }
      }

      public void readFrom(DataInputStream in) throws IOException, IllegalAccessException, InstantiationException
      {
         type = in.readByte();
         viewID = in.readLong();
         int flushParticipantsSize = in.readShort();
         if (flushParticipantsSize > 0)
         {
            flushParticipants = new ArrayList(flushParticipantsSize);
            for (int i = 0; i < flushParticipantsSize; i++)
            {
               flushParticipants.add(Util.readAddress(in));
            }
         }
      }
   }
}
