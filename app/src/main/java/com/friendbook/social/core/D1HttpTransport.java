package com.friendbook.social.core;

import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

/** One bounded HTTP attempt only. Endpoint, auth and account guards stay in D1Client. */
final class D1HttpTransport {
    private D1HttpTransport(){}
    static JSONObject send(URL url,String method,String token,JSONObject body)throws Exception{
        byte[] bytes=body==null?null:body.toString().getBytes(StandardCharsets.UTF_8);
        if(bytes!=null&&bytes.length>32768)throw new IOException("Comment request too large");
        HttpURLConnection connection=(HttpURLConnection)url.openConnection();
        try{
            connection.setConnectTimeout(10000);connection.setReadTimeout(15000);
            connection.setInstanceFollowRedirects(false);connection.setRequestMethod(method);
            connection.setRequestProperty("Authorization","Bearer "+token);
            connection.setRequestProperty("Accept","application/json");
            if(bytes!=null){
                connection.setDoOutput(true);connection.setFixedLengthStreamingMode(bytes.length);
                connection.setRequestProperty("Content-Type","application/json");
                try(OutputStream out=connection.getOutputStream()){out.write(bytes);}
            }
            int status=connection.getResponseCode();
            if(status<200||status>=300)throw new IOException("D1 request failed ("+status+"). Retry here; no Firebase write was made.");
            try(InputStream in=connection.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
                byte[] buffer=new byte[4096];int n;
                while((n=in.read(buffer))!=-1){
                    if(out.size()+n>1048576)throw new IOException("Response too large");
                    out.write(buffer,0,n);
                }
                return new JSONObject(out.toString("UTF-8"));
            }
        }finally{connection.disconnect();}
    }
}
