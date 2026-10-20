package com.jcraft.jsch;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TunnelScopeTest {
  @ParameterizedTest
  @ValueSource(
      strings = {"session", "shell", "exec", "sftp", "subsystem", "x11", "auth-agent@openssh.com"})
  void rejectsNonTunnelChannels(String type) {
    assertEquals(false, Session.supportsTunnelChannel(type));
  }

  @ParameterizedTest
  @ValueSource(strings = {"direct-tcpip"})
  void retainsTunnelChannels(String type) {
    assertEquals(true, Session.supportsTunnelChannel(type));
  }
}
