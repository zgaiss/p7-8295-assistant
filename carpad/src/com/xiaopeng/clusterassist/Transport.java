package com.xiaopeng.clusterassist;

import java.io.IOException;

/** 仪表侧文件通道（当前只实现 FTP；留出接口便于以后接 ADB）。 */
public interface Transport {
    String name();
    String describe();
    void put(String remote, byte[] data) throws IOException;
    byte[] get(String remote) throws IOException;
    long size(String remote);
    boolean ping();
    void close();
}
