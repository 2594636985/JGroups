

package org.jgroups.tests;


import org.jgroups.Channel;
import org.jgroups.JChannel;
import org.jgroups.blocks.GroupRequest;
import org.jgroups.blocks.RpcDispatcher;
import org.jgroups.util.RspList;
import org.jgroups.util.Util;


/**
 * Example for RpcDispatcher (see also MessageDispatcher). A remote method (print()) is group-invoked
 * periodically. The method is defined in each instance and is invoked whenever a remote method call
 * is received. The callee (although in this example, each callee is also a caller (peer principle))
 * has to define the public methods, and the caller uses one of the callRemoteMethods() methods to
 * invoke a remote method. CallRemoteMethods uses the core reflection API to lookup and dispatch
 * methods.
 *
 * @author Bela Ban
 * @version $Id: RpcDispatcherSimpleTest.java,v 1.1.2.1 2006/12/04 13:46:38 belaban Exp $
 */
public class RpcDispatcherSimpleTest {
    Channel channel;
    RpcDispatcher disp;
    RspList rsp_list;


    public int print(int number) throws Exception {
        System.out.println("print(" + number + ')');
        return number * 2;
    }


    public void start(String props, int num, long interval) throws Exception {
        channel=new JChannel(props);
        channel.setOpt(Channel.AUTO_RECONNECT, Boolean.TRUE);
        disp=new RpcDispatcher(channel, null, null, this);
        channel.connect("RpcDispatcherTestGroup");

        for(int i=0; i < num; i++) {
            Util.sleep(interval);
            rsp_list=disp.callRemoteMethods(null, "print", new Object[]{new Integer(i)},
                    new Class[]{int.class}, GroupRequest.GET_ALL, 0);
            System.out.println("Responses:\n" + rsp_list);
        }
        System.out.println("Closing channel");
        channel.close();
        System.out.println("Closing channel: -- done");

        System.out.println("Stopping dispatcher");
        disp.stop();
        System.out.println("Stopping dispatcher: -- done");
    }


    public static void main(String[] args) {
        int num=10;
        long interval=1000;
        String props=null;
        for(int i=0; i < args.length; i++) {
            if(args[i].equals("-num")) {
                num=Integer.parseInt(args[++i]);
                continue;
            }
            if(args[i].equals("-interval")) {
                interval=Long.parseLong(args[++i]);
                continue;
            }
            if(args[i].equals("-props")) {
                props=args[++i];
                continue;
            }
            help();
            return;
        }

        try {
            new RpcDispatcherSimpleTest().start(props, num, interval);
        }
        catch(Exception e) {
            System.err.println(e);
        }
    }

    private static void help() {
        System.out.println("RpcDispatcherTest [-help] [-props <properties>] [-num <number of msgs>] [-interval <sleep in ms between calls>]");
    }
}
