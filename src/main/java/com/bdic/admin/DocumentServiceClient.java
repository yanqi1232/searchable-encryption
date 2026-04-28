package com.bdic.admin;

import com.bdic.model.DocumentRequest;
import com.bdic.model.EncryptedData;
import com.bdic.model.LoginRequest;
import com.bdic.model.NetworkMessage;
import com.bdic.model.ServerResponse;

import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.List;

/**
 * 负责封装客户端与服务端之间的协议请求与响应读取。
 */
public class DocumentServiceClient {

    private final ObjectOutputStream out;
    private final ObjectInputStream in;

    public DocumentServiceClient(ObjectOutputStream out, ObjectInputStream in) {
        this.out = out;
        this.in = in;
    }

    public synchronized ServerResponse login(String username, String password) throws Exception {
        return sendAndRead(NetworkMessage.MessageType.LOGIN, new LoginRequest(username, password));
    }

    public synchronized ServerResponse register(String username, String password) throws Exception {
        return sendAndRead(NetworkMessage.MessageType.REGISTER, new LoginRequest(username, password));
    }

    public synchronized ServerResponse logout() throws Exception {
        return sendAndRead(NetworkMessage.MessageType.LOGOUT, null);
    }

    public synchronized ServerResponse upload(EncryptedData data) throws Exception {
        return sendAndRead(NetworkMessage.MessageType.UPLOAD, data);
    }

    public synchronized ServerResponse search(byte[] trapdoor) throws Exception {
        return sendAndRead(NetworkMessage.MessageType.SEARCH, trapdoor);
    }

    public synchronized ServerResponse listDocuments() throws Exception {
        return sendAndRead(NetworkMessage.MessageType.LIST_DOCUMENTS, null);
    }

    public synchronized ServerResponse downloadDocument(String docId) throws Exception {
        return sendAndRead(NetworkMessage.MessageType.DOWNLOAD_DOCUMENT, new DocumentRequest(docId));
    }

    public synchronized ServerResponse deleteDocument(String docId) throws Exception {
        return sendAndRead(NetworkMessage.MessageType.DELETE_DOCUMENT, new DocumentRequest(docId));
    }

    @SuppressWarnings("unchecked")
    public static List<EncryptedData> toEncryptedDataList(ServerResponse response) {
        return (List<EncryptedData>) response.getData();
    }

    private ServerResponse sendAndRead(NetworkMessage.MessageType type, Object payload) throws Exception {
        out.writeObject(new NetworkMessage(type, payload));
        out.flush();
        return readServerResponse();
    }

    private ServerResponse readServerResponse() throws Exception {
        NetworkMessage responseMessage = (NetworkMessage) in.readObject();
        Object payload = responseMessage.getPayload();
        if (!(payload instanceof ServerResponse)) {
            throw new IllegalStateException("Unexpected response payload: " + payload);
        }
        return (ServerResponse) payload;
    }
}
