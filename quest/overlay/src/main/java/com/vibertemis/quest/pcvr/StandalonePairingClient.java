package com.vibertemis.quest.pcvr;

import com.limelight.nvstream.http.ComputerDetails;
import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.cert.*;
import java.security.spec.*;
import java.util.LinkedHashSet;
import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.net.ssl.*;
import org.json.JSONObject;

/** First-use TLS is restricted to enrollment; human code comparison establishes
 * trust. No GameStream keys or APIs are used. All subsequent traffic is pinned. */
public final class StandalonePairingClient {
    static final String DOMAIN = "VIBERTEMIS-STANDALONE-1";
    public interface Progress { void comparing(String code); }
    private volatile boolean cancelled;
    private volatile HttpsURLConnection active;
    public void cancel() { cancelled = true; HttpsURLConnection c=active; if(c!=null)c.disconnect(); }
    private void checkCancelled() throws IOException { if(cancelled||Thread.currentThread().isInterrupted())throw new IOException("Cancelled"); }

    public HostPairing enroll(ComputerDetails pc, Progress progress) throws Exception {
        LinkedHashSet<String> addresses=new LinkedHashSet<>();
        for(ComputerDetails.AddressTuple a:new ComputerDetails.AddressTuple[]{pc.activeAddress,pc.manualAddress,pc.localAddress,pc.remoteAddress,pc.ipv6Address})
            if(a!=null)addresses.add(a.address);
        KeyPairGenerator generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);
        KeyPair keys=generator.generateKeyPair();
        byte[] nonceBytes=new byte[32];new SecureRandom().nextBytes(nonceBytes);
        String nonce=HostPairing.hex(nonceBytes);
        String key=android.util.Base64.encodeToString(keys.getPublic().getEncoded(),android.util.Base64.NO_WRAP);
        String keyHash=hash(keys.getPublic().getEncoded());
        URL base=null;JSONObject challenge=null;String pin=null;String pem=null;
        boolean completed=false;
        try {
            for(String address:addresses) {
                checkCancelled();
                try {
                    URL candidate=new URL("https",address,28540,"/pairing/begin");
                    Reply reply=request(candidate,null,new JSONObject().put("schema",1).put("client_key",key).put("client_nonce",nonce),false);
                    JSONObject c=reply.json;
                    if(c.getInt("schema")!=1||!hex(c.getString("session_id"),64)||!hex(c.getString("server_nonce"),64))
                        throw new SetupFailure("Invalid PC pairing response. Cancel and retry on both devices.");
                    X509Certificate certificate=parseCertificate(c.getString("cert_pem"));
                    if(!reply.pin.equals(hash(certificate.getEncoded())))throw new SetupFailure("PC identity changed. Cancel and retry on both devices.");
                    pin=reply.pin;pem=c.getString("cert_pem");base=candidate;challenge=c;break;
                } catch(SetupFailure e){throw e;} catch(Exception e){checkCancelled();}
            }
            if(challenge==null)throw new SetupFailure("Could not reach VR Host Manager. Start hosting on the PC and choose Pair headset. Away from home, connect a VPN to home; Moonlight ports alone are not enough.");
            String id=challenge.getString("session_id"),serverNonce=challenge.getString("server_nonce");
            progress.comparing(comparisonCode(pin,keyHash,nonce,serverNonce,id));
            long deadline=android.os.SystemClock.elapsedRealtime()+120000;
            JSONObject poll=proof(keys.getPrivate(),"POLL",id,nonce,serverNonce,pin);
            while(android.os.SystemClock.elapsedRealtime()<deadline) {
                checkCancelled();
                JSONObject result=request(new URL(base,"/pairing/poll"),pin,poll,false).json;
                if(result.getInt("schema")!=1)throw new SetupFailure("Update VR Host Manager and retry pairing.");
                String state=result.getString("state");
                if(state.equals("denied"))throw new SetupFailure("Pairing was rejected on the PC. Choose Pair headset there to retry.");
                if(state.equals("approved")) {
                    String device=result.getString("device_id");
                    if(!hex(device,32))throw new SetupFailure("Invalid headset identity. Retry pairing.");
                    byte[] encrypted=android.util.Base64.decode(result.getString("encrypted_token"),android.util.Base64.NO_WRAP);
                    if(encrypted.length!=256)throw new SetupFailure("Invalid pairing credentials. Retry pairing.");
                    Cipher cipher=Cipher.getInstance("RSA/ECB/OAEPPadding");
                    cipher.init(Cipher.DECRYPT_MODE,keys.getPrivate(),new OAEPParameterSpec("SHA-256","MGF1",MGF1ParameterSpec.SHA256,
                        new PSource.PSpecified((DOMAIN+"-TOKEN\n1\n"+id).getBytes(StandardCharsets.UTF_8))));
                    byte[] plaintext=cipher.doFinal(encrypted);
                    String token=new String(plaintext,StandardCharsets.US_ASCII);java.util.Arrays.fill(plaintext,(byte)0);
                    if(!hex(token,64))throw new SetupFailure("Invalid pairing credentials. Retry pairing.");
                    HostPairing paired=HostPairing.parse(new JSONObject().put("host_address",new ComputerDetails.AddressTuple(base.getHost(),28540).toString())
                        .put("certpin",pin).put("cert_pem",pem).put("token",token).put("device_id",device).put("pairing_kind","standalone").toString());
                    checkCancelled();completed=true;return paired;
                }
                if(!state.equals("pending"))throw new SetupFailure("Pairing session changed. Cancel and retry on both devices.");
                Thread.sleep(1000);
            }
            throw new SetupFailure("Pairing timed out. Choose Pair headset on the PC, then retry here.");
        } finally {
            if(!completed&&challenge!=null&&base!=null&&pin!=null) {
                try {request(new URL(base,"/pairing/cancel"),pin,proof(keys.getPrivate(),"CANCEL",challenge.getString("session_id"),nonce,challenge.getString("server_nonce"),pin),true);}catch(Exception ignored){}
            }
            // Providers may not support destroy. Keys are never persisted or logged.
            try {keys.getPrivate().destroy();}catch(Exception ignored){}
        }
    }
    static String hash(byte[] data) throws Exception {return HostPairing.hex(MessageDigest.getInstance("SHA-256").digest(data));}
    static boolean hex(String value,int count){return value.matches("[0-9a-f]{"+count+"}");}
    static String comparisonCode(String pin,String key,String client,String server,String id) throws Exception {
        String h=hash((DOMAIN+"-CODE\n1\n"+pin+"\n"+key+"\n"+client+"\n"+server+"\n"+id).getBytes(StandardCharsets.UTF_8)).substring(0,16).toUpperCase(java.util.Locale.ROOT);
        return h.substring(0,4)+"-"+h.substring(4,8)+"-"+h.substring(8,12)+"-"+h.substring(12);
    }
    static byte[] transcript(String action,String id,String nonce,String server,String pin) {
        return (DOMAIN+"-"+action+"\n1\n"+id+"\n"+nonce+"\n"+server+"\n"+pin).getBytes(StandardCharsets.UTF_8);
    }
    private static JSONObject proof(PrivateKey key,String action,String id,String nonce,String server,String pin) throws Exception {
        Signature signature;
        try{signature=Signature.getInstance("RSASSA-PSS");}catch(NoSuchAlgorithmException e){signature=Signature.getInstance("SHA256withRSA/PSS");}
        signature.setParameter(new PSSParameterSpec("SHA-256","MGF1",MGF1ParameterSpec.SHA256,32,1));
        signature.initSign(key);signature.update(transcript(action,id,nonce,server,pin));
        return new JSONObject().put("schema",1).put("session_id",id).put("signature",android.util.Base64.encodeToString(signature.sign(),android.util.Base64.NO_WRAP));
    }
    private static X509Certificate parseCertificate(String pem) throws Exception {
        X509Certificate c=(X509Certificate)CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(pem.getBytes(StandardCharsets.US_ASCII)));
        c.checkValidity();return c;
    }
    private static final class Reply {final JSONObject json;final String pin;Reply(JSONObject j,String p){json=j;pin=p;}}
    private Reply request(URL url,String pin,JSONObject body,boolean cleanup) throws Exception {
        if(!cleanup)checkCancelled();
        final String[] observed={null};
        X509TrustManager trust=new X509TrustManager(){
            public X509Certificate[] getAcceptedIssuers(){return new X509Certificate[0];}
            public void checkClientTrusted(X509Certificate[] c,String a)throws CertificateException{throw new CertificateException("Unsupported");}
            public void checkServerTrusted(X509Certificate[] c,String a)throws CertificateException{
                try{
                    if(c==null||c.length==0)throw new CertificateException("Missing identity");c[0].checkValidity();String actual=hash(c[0].getEncoded());
                    if(pin!=null&&!pin.equals(actual))throw new CertificateException("PC identity changed");observed[0]=actual;
                }catch(CertificateException e){throw e;}catch(Exception e){throw new CertificateException(e);}
            }
        };
        SSLContext tls=SSLContext.getInstance("TLS");tls.init(null,new TrustManager[]{trust},null);
        HttpsURLConnection c=(HttpsURLConnection)url.openConnection();if(!cleanup)active=c;
        try{
            if(!cleanup)checkCancelled();
            c.setSSLSocketFactory(tls.getSocketFactory());
            c.setHostnameVerifier((host,session)->{
                try{return host.equalsIgnoreCase(url.getHost())&&observed[0]!=null&&observed[0].equals(hash(session.getPeerCertificates()[0].getEncoded()));}catch(Exception e){return false;}
            });
            c.setInstanceFollowRedirects(false);c.setUseCaches(false);c.setConnectTimeout(cleanup?1000:3000);c.setReadTimeout(cleanup?1000:5000);
            c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");
            byte[] bytes=body.toString().getBytes(StandardCharsets.UTF_8);c.setFixedLengthStreamingMode(bytes.length);
            try(OutputStream out=c.getOutputStream()){out.write(bytes);}
            int status=c.getResponseCode();
            if(status==404)throw new SetupFailure("Update VR Host Manager on the PC, then choose Pair headset. Vibeshine does not need updating.");
            if(status==429)throw new SetupFailure("Too many pairing requests. Close Pair headset on the PC, wait a minute and retry.");
            if(status!=200)throw new SetupFailure("Pairing is not available. On the PC, close and reopen Pair headset, then retry here.");
            try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
                byte[] buffer=new byte[2048];int n;while((n=in.read(buffer))!=-1){if(!cleanup)checkCancelled();if(out.size()+n>16384)throw new IOException("Pairing response too large");out.write(buffer,0,n);}
                if(observed[0]==null)throw new IOException("Missing PC identity");return new Reply(new JSONObject(out.toString("UTF-8")),observed[0]);
            }
        }finally{if(!cleanup)active=null;c.disconnect();}
    }
    public static final class SetupFailure extends IOException {public SetupFailure(String text){super(text);}}
}
