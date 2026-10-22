package com.jcraft.jsch;
public class ReceivePolicyRedProbe {
 public static void main(String[] args) throws Exception {
  Session s=new JSch().getSession("fixture","127.0.0.1",22);
  s.setConfig("channel_receive_budget_policy","allocated");
  ChannelDirectTCPIP c=new ChannelDirectTCPIP();c.init();c.setSession(s);
  c.setLocalWindowSizeMax(4194304);c.setLocalWindowSize(4194304);
  c.getInputStream();c.prepareReceive();
  if(s.reservedReceiveMemory()!=0)throw new AssertionError("Idle channel reserved "+s.reservedReceiveMemory()+" bytes; expected zero for 128-channel compatibility");
 }
}
