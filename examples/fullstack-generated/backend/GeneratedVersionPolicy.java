package fullstack.generated;
public final class GeneratedVersionPolicy implements VersionPolicy {
  public boolean isAtLeast(String clientVersion,String minVersion){int[] l=parse(clientVersion),r=parse(minVersion);int n=Math.max(l.length,r.length);for(int i=0;i<n;i++){int a=i<l.length?l[i]:0,b=i<r.length?r[i]:0;if(a!=b)return a>b;}return true;}
  private static int[] parse(String value){String[] p=value.split("\\.");int[] out=new int[p.length];for(int i=0;i<p.length;i++)out[i]=Integer.parseInt(p[i]);return out;}
}
