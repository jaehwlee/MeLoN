package kr.ac.mju.idpl.melon.rpc;

import java.io.IOException;

import org.apache.hadoop.ipc.ProtocolInfo;
import org.apache.hadoop.ipc.VersionedProtocol;
import org.apache.hadoop.security.token.TokenInfo;
import org.apache.hadoop.yarn.security.client.ClientToAMTokenSelector;

@TokenInfo(ClientToAMTokenSelector.class)
@ProtocolInfo(
    protocolName = "kr.ac.mju.idpl.melon.rpc.RPCProtocol",
    protocolVersion = 0)

public interface RPCProtocol extends VersionedProtocol {
	public long versionID = 0;
	public String heartBeat() throws IOException;
}