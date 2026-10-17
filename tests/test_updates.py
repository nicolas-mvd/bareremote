"""Production update parser, package trust, cache lifecycle and cancellation checks."""
from pathlib import Path
import subprocess
import tempfile
import unittest

STUBS = {
"android/content/SharedPreferences.java": """package android.content;
public interface SharedPreferences {String getString(String key,String value);long getLong(String key,long value);Editor edit();
interface Editor {Editor putString(String key,String value);Editor putLong(String key,long value);Editor remove(String key);void apply();boolean commit();}}""",
"android/content/Context.java": """package android.content;
public class Context {public static final int MODE_PRIVATE=0; public final Prefs preferences=new Prefs();public final android.content.pm.PackageManager pm=new android.content.pm.PackageManager();
public Context getApplicationContext(){return this;}public SharedPreferences getSharedPreferences(String n,int m){return preferences;}
public android.content.pm.PackageManager getPackageManager(){return pm;}public String getPackageName(){return \"uy.nico.bareremote\";} public java.io.File getCacheDir(){return new java.io.File(System.getProperty(\"java.io.tmpdir\"));}
public static class Prefs implements SharedPreferences,SharedPreferences.Editor {java.util.Map<String,Object> data=new java.util.HashMap<>();
public String getString(String k,String v){return data.containsKey(k)?(String)data.get(k):v;}public long getLong(String k,long v){return data.containsKey(k)?(Long)data.get(k):v;}
public SharedPreferences.Editor edit(){return this;}public SharedPreferences.Editor putString(String k,String v){data.put(k,v);return this;}
public SharedPreferences.Editor putLong(String k,long v){data.put(k,v);return this;}public SharedPreferences.Editor remove(String k){data.remove(k);return this;}public void apply(){}public boolean commit(){return true;}}}""",
"android/content/Intent.java": "package android.content; public class Intent {public Intent(Context c,Class<?> type){}}",
"android/app/Activity.java": "package android.app;public class Activity extends android.content.Context {public void startActivity(android.content.Intent i){}}",
"android/content/pm/PackageInfo.java": """package android.content.pm;public class PackageInfo {public String packageName,versionName;public int versionCode;
public Signature[] signatures;public SigningInfo signingInfo;public long getLongVersionCode(){return versionCode;}}""",
"android/content/pm/PackageManager.java": """package android.content.pm; public class PackageManager {
public static final int GET_SIGNING_CERTIFICATES=1,GET_SIGNATURES=2;public PackageInfo installed,candidate;
public PackageInfo getPackageInfo(String p,int f)throws NameNotFoundException{return installed;}public PackageInfo getPackageArchiveInfo(String p,int f){return candidate;}
public static class NameNotFoundException extends Exception {private static final long serialVersionUID=1;}}""",
"android/content/pm/Signature.java": "package android.content.pm;public class Signature {byte[] bytes;public Signature(byte[] bytes){this.bytes=bytes;}public byte[] toByteArray(){return bytes;}}",
"android/content/pm/SigningInfo.java": "package android.content.pm;public class SigningInfo {Signature[] signatures;public SigningInfo(Signature[] s){signatures=s;}public Signature[] getApkContentsSigners(){return signatures;}}",
"android/os/Build.java": "package android.os; public class Build {public static class VERSION {public static int SDK_INT=35;}}",
"android/os/Looper.java": "package android.os;public class Looper {public static Looper getMainLooper(){return new Looper();}}",
"android/os/Handler.java": """package android.os;public class Handler {static java.util.Queue<Runnable> queued=new java.util.concurrent.ConcurrentLinkedQueue<>();public Handler(Looper l){}
public boolean post(Runnable runnable){queued.add(runnable);return true;}public static void drain(){Runnable r;while((r=queued.poll())!=null)r.run();}}""",
"uy/nico/bareremote/UpdateActivity.java": "package uy.nico.bareremote;public class UpdateActivity {}",
}

class UpdateTests(unittest.TestCase):
    def test_update_security_and_lifecycle(self):
        root = Path(__file__).resolve().parents[1]
        java = root / "app/src/main/java/uy/nico/bareremote"
        with tempfile.TemporaryDirectory(prefix="bareremote-updates-") as temporary:
            target=Path(temporary)
            sources=[]
            for name, source in STUBS.items():
                path=target/name
                path.parent.mkdir(parents=True,exist_ok=True)
                path.write_text(source)
                sources.append(str(path))
            subprocess.run(["javac","--release","8","-d",temporary,*sources,
                str(java/"UpdatePolicy.java"),str(java/"AppUpdates.java"),str(root/"tests/UpdateTest.java")],
                check=True,capture_output=True,text=True)
            result=subprocess.run(["java","-cp",temporary,"uy.nico.bareremote.UpdateTest"],
                check=False,capture_output=True,text=True,timeout=10)
            self.assertEqual(result.returncode,0,result.stdout+result.stderr)
            print(result.stdout.strip())

if __name__ == "__main__":
    unittest.main()
