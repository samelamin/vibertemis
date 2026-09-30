package com.vibertemis.quest.pcvr;

import android.content.Context;
import com.limelight.binding.PlatformBinding;
import com.limelight.computers.IdentityManager;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.LimelightCryptoProvider;
import com.limelight.nvstream.http.NvHTTP;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.LinkedHashSet;
import javax.net.ssl.*;
import org.json.JSONObject;

/** Enrolls VR using the existing GameStream certificate, without changing it. */
public final class ExistingPairingBootstrap {
    private final Context context;
    private volatile boolean cancelled;
    private volatile HttpsURLConnection active;
    public ExistingPairingBootstrap(Context context) { this.context = context.getApplicationContext(); }
    public void cancel() { cancelled = true; HttpsURLConnection c = active; if (c != null) c.disconnect(); }

    public HostPairing enroll(ComputerDetails pc) throws Exception {
        if (pc.serverCert == null) throw new IOException("Pair this PC for screen gaming first.");
        LimelightCryptoProvider keys = PlatformBinding.getCryptoProvider(context);
        String hostPin = fingerprint(pc.serverCert);
        LinkedHashSet<ComputerDetails.AddressTuple> addresses = new LinkedHashSet<>();
        addresses.add(pc.activeAddress); addresses.add(pc.manualAddress); addresses.add(pc.localAddress);
        addresses.add(pc.remoteAddress); addresses.add(pc.ipv6Address); addresses.remove(null);
        Exception last = null;
        for (ComputerDetails.AddressTuple address : addresses) {
            if (cancelled) throw new IOException("Cancelled");
            URL base;
            try {
                NvHTTP nv = new NvHTTP(address, pc.httpsPort, new IdentityManager(context).getUniqueId(), pc.serverCert, keys);
                base = nv.getHttpsUrl(false).url();
                JSONObject caps = request(new URL(base, "/api/vr/capabilities"), hostPin, keys, null);
                if (caps.getInt("schema") != 1 || caps.getInt("bootstrap") != 1)
                    throw new SetupFailure("Update Vibeshine on the PC to enable automatic VR pairing.");
                if (!caps.getBoolean("bridge_ready"))
                    throw new SetupFailure("On the PC, open VibertemisVR Host Manager and choose Setup VR. Then retry here.");
            } catch (SetupFailure e) { throw e; }
            catch (Exception e) { last = e; continue; }
            byte[] nonceBytes = new byte[32]; new SecureRandom().nextBytes(nonceBytes);
            String nonce = HostPairing.hex(nonceBytes);
            JSONObject grant = request(new URL(base, "/api/vr/bootstrap"), hostPin, keys,
                new JSONObject().put("schema",1).put("client_nonce",nonce));
            validateGrant(grant, nonce, hostPin);
            String companionPin = grant.getString("companion_cert_sha256");
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(keys.getClientPrivateKey());
            signature.update(canonical(nonce, grant.getString("grant"), hostPin, companionPin,
                grant.getString("client_uuid")));
            String encoded = android.util.Base64.encodeToString(signature.sign(), android.util.Base64.NO_WRAP);
            URL companion = new URL("https", base.getHost(), grant.getInt("port"), "/pairing/redeem");
            JSONObject result;
            try {
                result = request(companion, companionPin, null,
                    new JSONObject().put("schema",1).put("grant",grant.getString("grant"))
                        .put("client_nonce",nonce).put("signature",encoded));
            } catch (Exception e) {
                if (cancelled) throw new IOException("Cancelled");
                throw new SetupFailure("Could not reach the VR host. Use your home network or a VPN to home; GameStream port forwarding alone does not carry VR.");
            }
            validateRedemption(result, grant);
            result.put("host_address",new ComputerDetails.AddressTuple(base.getHost(),grant.getInt("port")).toString());
            return HostPairing.parse(result.toString());
        }
        if (cancelled) throw new IOException("Cancelled");
        throw new SetupFailure("Could not reach the paired PC. Check its address and network, and make sure the VR-enabled Vibeshine update is installed.");
    }

    static byte[] canonical(String nonce,String grant,String host,String companion,String uuid) {
        return ("VIBERTEMIS-VR-ENROLL-1\n"+nonce+"\n"+grant+"\n"+host+"\n"+companion+"\n"+uuid)
            .getBytes(StandardCharsets.UTF_8);
    }
    static void validateGrant(JSONObject g,String nonce,String hostPin) throws Exception {
        if (g.getInt("schema")!=1 || !nonce.equals(g.getString("client_nonce"))
            || !hostPin.equals(g.getString("host_cert_sha256"))
            || !g.getString("grant").matches("[0-9a-f]{64}")
            || !g.getString("companion_cert_sha256").matches("[0-9a-f]{64}")
            || !g.getString("client_uuid").matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")
            || g.getInt("port")<1 || g.getInt("port")>65535 || g.getLong("expires_unix")<=0)
            throw new SetupFailure("PC pairing response did not match this request. Retry Setup VR.");
    }
    static void validateRedemption(JSONObject r,JSONObject g) throws Exception {
        if (r.getInt("schema")!=1 || !r.getString("host_cert_sha256").equals(g.getString("host_cert_sha256"))
            || !r.getString("client_uuid").equals(g.getString("client_uuid"))
            || !r.getString("certpin").equals(g.getString("companion_cert_sha256"))
            || !r.getString("device_id").matches("[0-9a-f]{32}"))
            throw new SetupFailure("VR pairing identity changed. Retry Setup VR.");
    }
    private static String fingerprint(X509Certificate cert) throws Exception {
        return HostPairing.hex(MessageDigest.getInstance("SHA-256").digest(cert.getEncoded()));
    }
    private JSONObject request(URL url,String pin,LimelightCryptoProvider keys,JSONObject body) throws Exception {
        if (cancelled) throw new IOException("Cancelled");
        X509TrustManager trust = new X509TrustManager() {
            public X509Certificate[] getAcceptedIssuers(){return new X509Certificate[0];}
            public void checkClientTrusted(X509Certificate[] c,String a) throws CertificateException {throw new CertificateException("Unsupported");}
            public void checkServerTrusted(X509Certificate[] c,String a) throws CertificateException {
                try { if(c==null||c.length==0||!pin.equals(fingerprint(c[0])))throw new CertificateException("PC certificate changed"); }
                catch(CertificateException e){throw e;}catch(Exception e){throw new CertificateException(e);}
            }
        };
        KeyManager[] managers = keys==null ? null : new KeyManager[]{new X509KeyManager(){
            public String[] getClientAliases(String type,Principal[] issuers){return new String[]{"moonlight"};}
            public String chooseClientAlias(String[] types,Principal[] issuers,java.net.Socket socket){return "moonlight";}
            public String[] getServerAliases(String type,Principal[] issuers){return null;}
            public String chooseServerAlias(String type,Principal[] issuers,java.net.Socket socket){return null;}
            public X509Certificate[] getCertificateChain(String alias){return new X509Certificate[]{keys.getClientCertificate()};}
            public PrivateKey getPrivateKey(String alias){return keys.getClientPrivateKey();}
        }};
        SSLContext tls=SSLContext.getInstance("TLS");tls.init(managers,new TrustManager[]{trust},null);
        HttpsURLConnection c=(HttpsURLConnection)url.openConnection();active=c;
        try {
            if(cancelled)throw new IOException("Cancelled");
            c.setSSLSocketFactory(tls.getSocketFactory());
            c.setHostnameVerifier((host,session)->{
                try{return host.equalsIgnoreCase(url.getHost())&&pin.equals(fingerprint((X509Certificate)session.getPeerCertificates()[0]));}
                catch(Exception e){return false;}
            });
            c.setInstanceFollowRedirects(false);c.setUseCaches(false);c.setConnectTimeout(3000);c.setReadTimeout(8000);
            c.setRequestMethod(body==null?"GET":"POST");
            if(body!=null){byte[] bytes=body.toString().getBytes(StandardCharsets.UTF_8);c.setDoOutput(true);c.setFixedLengthStreamingMode(bytes.length);c.setRequestProperty("Content-Type","application/json");try(java.io.OutputStream out=c.getOutputStream()){out.write(bytes);}}
            int status=c.getResponseCode();
            if(status==404)throw new SetupFailure("Update Vibeshine on the PC to enable automatic VR pairing.");
            if(status==401||status==403)throw new SetupFailure("The PC no longer trusts this app. Pair it again for screen gaming, then retry Setup VR.");
            if(status!=200)throw new SetupFailure("PC setup is not ready. Open Setup VR in the Windows VR Host Manager and retry.");
            try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
                byte[] buffer=new byte[2048];int n;
                while((n=in.read(buffer))!=-1){if(cancelled)throw new IOException("Cancelled");if(out.size()+n>16384)throw new IOException("Pairing response too large");out.write(buffer,0,n);}
                return new JSONObject(new String(out.toByteArray(),StandardCharsets.UTF_8));
            }
        } finally {active=null;c.disconnect();}
    }
    public static final class SetupFailure extends IOException { public SetupFailure(String message){super(message);} }
}
