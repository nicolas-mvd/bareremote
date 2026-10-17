package uy.nico.bareremote;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Handler;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class UpdateTest {
    static int assertions;
    static final String URL = "https://github.com/nicolas-mvd/bareremote/releases/download/v9.0/BareRemote.apk";
    static final String SHA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    static String json(int code) { return "{\"versionCode\":"+code+",\"versionName\":\"9.0\",\"apkUrl\":\""+URL+"\",\"sha256\":\""+SHA+"\",\"size\":123}"; }
    static void yes(boolean result, String name) { assertions++; if (!result) throw new AssertionError(name); }
    interface Throwing { void run() throws Exception; }
    static void rejects(Throwing test, String name) throws Exception {
        assertions++;
        try { test.run(); } catch (IllegalArgumentException | IOException ex) { return; }
        throw new AssertionError("Accepted " + name);
    }
    static Method method(Class<?> type, String name, Class<?>... parameters) throws Exception {
        Method method=type.getDeclaredMethod(name, parameters); method.setAccessible(true); return method;
    }
    static Object invoke(Method method, Object target, Object... args) throws Exception {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException ex) { if (ex.getCause() instanceof Exception) throw (Exception) ex.getCause(); throw ex; }
    }
    static PackageInfo info(String name, int code, byte[]... signatures) {
        PackageInfo info=new PackageInfo(); info.packageName=name; info.versionCode=code; info.versionName="installed";
        info.signatures=new Signature[signatures.length];
        for(int i=0;i<signatures.length;i++) info.signatures[i]=new Signature(signatures[i]);
        info.signingInfo=new SigningInfo(info.signatures); return info;
    }
    public static void main(String[] args) throws Exception {
        UpdatePolicy.Metadata metadata=UpdatePolicy.parse(json(9));
        yes(metadata.code==9 && metadata.size==123 && metadata.name.equals("9.0") && metadata.url.equals(URL), "valid manifest");
        yes(UpdatePolicy.parse(" \n"+json(9)+"\t ").code==9,"JSON whitespace");
        yes(UpdatePolicy.parse(json(9).replace("9.0", "9\\u002e0")).name.equals("9.0"),"JSON escapes");
        String[] invalid={"", "{}", json(9)+"junk", json(9)+"{}", json(9).replace("123}","123,\"extra\":1}"),
            json(9).replace("123}","123,\"size\":123}"), json(9).replace("123}","123,}"),
            json(9).replace("\"size\":123", "\"size\":\"123\""), json(9).replace("123}","1.23}"),
            json(9).replace("123}","1e2}"),json(9).replace("123}","0123}"),json(9).replace("123}","-123}"),
            json(9).replace("123}","0}"),json(9).replace("123}","8388609}"),
            json(9).replace("123}","99999999999999999999999999}"),
            json(9).replace("\"versionCode\":9", "\"versionCode\":2147483648"),json(0),json(-1),
            json(9).replace(SHA, SHA.toUpperCase()), json(9).replace("\"versionName\":\"9.0\"","\"versionName\":null"),
            json(9).replace("9.0", "bad\\nescape"),json(9).replace("9.0", "bad\\qescape")};
        for(int i=0;i<invalid.length;i++){ final String s=invalid[i]; rejects(()->UpdatePolicy.parse(s),"manifest "+i); }
        rejects(()->UpdatePolicy.parse(new String(new char[16385])), "oversize metadata");
        String[] badUrls={URL.replace("https:","http:"),URL.replace("github.com","evilgithub.com"),
            URL.replace("github.com", "github.com.evil.test"),URL.replace("github.com", "u:p@github.com"),
            URL.replace("github.com", "github.com:443"),URL.replace("bareremote", "other"),
            URL.replace("BareRemote.apk", "BareRemoteXapk"),URL+"?x=1",URL+"#x",URL.replace("v9.0", ".."),
            URL.replace("v9.0", "v9%2Ffoo"),URL.replace("BareRemote.apk", "Other.apk")};
        for(int i=0;i<badUrls.length;i++){ final String u=badUrls[i]; rejects(()->UpdatePolicy.validate(9,"9.0",u,SHA,123),"asset "+i); }
        yes(UpdatePolicy.downloadHost(URL),"github redirect host");
        yes(UpdatePolicy.downloadHost("https://release-assets.githubusercontent.com/x?signature=secret"),"release redirect host");
        yes(UpdatePolicy.downloadHost("https://objects.githubusercontent.com/x"),"legacy redirect host");
        for(String url:new String[]{"http://github.com/x","https://evil.test/x","https://127.0.0.1/x","https://github.com:443/x","https://user@github.com/x","https://github.com/x#fragment","::::"})
            yes(!UpdatePolicy.downloadHost(url),"bad redirect host");
        byte[] digest=MessageDigest.getInstance("SHA-256").digest(new byte[]{1,2,3});
        yes(UpdatePolicy.digestMatches(UpdatePolicy.hex(digest),digest),"correct digest");
        yes(!UpdatePolicy.digestMatches(SHA,digest),"wrong digest");
        byte[][] first={{1,2},{3,4}}, reverse={{3,4},{1,2}};
        yes(UpdatePolicy.sameSigners(first,reverse),"signer set order");
        yes(!UpdatePolicy.sameSigners(first,new byte[][]{{1,2},{1,2}}),"duplicate signers");
        yes(!UpdatePolicy.sameSigners(first,new byte[][]{{1,2}}),"missing signer");
        yes(!UpdatePolicy.sameSigners(new byte[0][],new byte[0][]),"empty signers");
        yes(!UpdatePolicy.sameSigners(null,first),"null signers");

        Context context=new Context();
        context.pm.installed=info(context.getPackageName(),5,new byte[]{1,2});
        AppUpdates updates=new AppUpdates(context);
        yes(updates.available()==null,"no cached update");
        context.preferences.edit().putString("metadata",json(9)).putLong("attempt",System.currentTimeMillis()).apply();
        yes(updates.available().versionCode==9,"newer cached release");
        final int[] callbacks={0};
        updates.check(false,(release,error)->{yes(release.versionCode==9 && error==null,"cached callback");callbacks[0]++;});
        Handler.drain(); yes(callbacks[0]==1,"callback delivered once");
        context.preferences.edit().putString("metadata",json(5)).apply();
        yes(updates.available()==null,"equal installed version hidden");
        updates.check(false,(release,error)->{yes(release==null && error==null,"up to date callback");callbacks[0]++;});
        Handler.drain(); yes(callbacks[0]==2,"up to date callback once");
        context.preferences.edit().putString("metadata",json(4)).apply();
        yes(updates.available()==null,"older cached release hidden");
        context.preferences.edit().putString("metadata",json(9)).apply();
        updates.check(false,(release,error)->callbacks[0]++);
        updates.cancel(); Handler.drain(); yes(callbacks[0]==2,"cancel suppresses queued callback");
        updates.check(false,(release,error)->callbacks[0]+=100);
        updates.check(false,(release,error)->callbacks[0]++);
        Handler.drain();yes(callbacks[0]==3,"new generation suppresses old callback");
        Method parse=method(AppUpdates.class,"parse",String.class);
        yes(((AppUpdates.Release)invoke(parse,null,json(9))).versionCode==9,"backend real metadata parser");
        rejects(()->invoke(parse,null,json(9)+"junk"),"backend invalid metadata");

        AppUpdates.Release release=new AppUpdates.Release(9,"9.0",URL,SHA,123);
        Method verify=method(AppUpdates.class,"verifyPackage",File.class,AppUpdates.Release.class);
        context.pm.candidate=info(context.getPackageName(),9,new byte[]{1,2});
        invoke(verify,updates,new File("candidate.apk"),release);assertions++;
        context.pm.candidate=info("other.package",9,new byte[]{1,2});
        rejects(()->invoke(verify,updates,new File("candidate.apk"),release),"wrong package");
        context.pm.candidate=info(context.getPackageName(),8,new byte[]{1,2});
        rejects(()->invoke(verify,updates,new File("candidate.apk"),release),"wrong metadata code");
        context.pm.candidate=info(context.getPackageName(),5,new byte[]{1,2});
        rejects(()->invoke(verify,updates,new File("candidate.apk"),new AppUpdates.Release(5,"5",URL,SHA,123)),"same version");
        context.pm.candidate=info(context.getPackageName(),4,new byte[]{1,2});
        rejects(()->invoke(verify,updates,new File("candidate.apk"),new AppUpdates.Release(4,"4",URL,SHA,123)),"downgrade");
        context.pm.candidate=info(context.getPackageName(),9,new byte[]{9,9});
        rejects(()->invoke(verify,updates,new File("candidate.apk"),release),"wrong signing cert");
        context.pm.candidate=null;
        rejects(()->invoke(verify,updates,new File("candidate.apk"),release),"unparseable package");
        android.os.Build.VERSION.SDK_INT=26;
        context.pm.candidate=info(context.getPackageName(),9,new byte[]{1,2});
        invoke(verify,updates,new File("candidate.apk"),release);assertions++;
        context.pm.candidate=info(context.getPackageName(),9,new byte[]{9,9});
        rejects(()->invoke(verify,updates,new File("candidate.apk"),release),"API26 wrong signing cert");
        android.os.Build.VERSION.SDK_INT=35;

        Class<?> operation=Class.forName("uy.nico.bareremote.AppUpdates$Operation");
        Constructor<?> constructor=operation.getDeclaredConstructor(); constructor.setAccessible(true);
        Object op=constructor.newInstance();
        CountDownLatch closing=new CountDownLatch(1), unblock=new CountDownLatch(1);
        HttpURLConnection blocking=new HttpURLConnection(new URL("https://github.com")) {
            public void disconnect(){ closing.countDown(); try {unblock.await(3,TimeUnit.SECONDS);}catch(InterruptedException ex){Thread.currentThread().interrupt();} }
            public boolean usingProxy(){return false;} public void connect(){}
        };
        invoke(method(operation,"connection",HttpURLConnection.class),op,blocking);
        long start=System.nanoTime(); invoke(method(operation,"cancel"),op);
        yes(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<200,"cancel returns without blocking on disconnect");
        yes(closing.await(1,TimeUnit.SECONDS),"cancel closes network on worker");
        rejects(()->invoke(method(operation,"ensure"),op),"cancelled operation stops");
        unblock.countDown();
        Object timeout=constructor.newInstance();
        invoke(method(operation,"deadline",long.class),timeout,20L);
        Thread.sleep(80);
        rejects(()->invoke(method(operation,"ensure"),timeout),"total deadline expires");
        invoke(method(operation,"close"),timeout);
        System.out.println(assertions+" update assertions passed");
    }
}
