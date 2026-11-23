package com.friendbook.social.core;

import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

/** One bounded HTTP attempt only. Endpoint, auth and account guards stay in D1Client. */
final class D1HttpTransport {
    private D1HttpTransport(){}
    static final class HttpStatusException extends IOException {
        final int status;
        HttpStatusException(int status) { super("D1 request failed ("+status+")"); this.status=status; }
    }
    static JSONObject send(URL url,String method,String token,JSONObject body)throws Exception{
        byte[] bytes=body==null?null:body.toString().getBytes(StandardCharsets.UTF_8);
        if(bytes!=null&&bytes.length>32768)throw new IOException("Comment request too large");
        try (okhttp3.Response response = CloudflareHttp.openEngagement(url.toString(),method,token,bytes)) {
            if (!response.isSuccessful()) throw new HttpStatusException(response.code());
            return new JSONObject(CloudflareHttp.readUtf8(response.body(),1048576));
        }
    }
}
