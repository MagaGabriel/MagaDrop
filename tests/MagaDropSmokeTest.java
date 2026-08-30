import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Comparator;
import java.util.concurrent.Executors;

public class MagaDropSmokeTest {
 public static void main(String[] args)throws Exception{
  Path temp=Files.createTempDirectory("magadrop-smoke-");
  try{
   MagaDrop.pastaWeb=Paths.get("web").toAbsolutePath().normalize();MagaDrop.pastaUploads=temp;MagaDrop.codigoAcesso="123456";
   HttpServer server=HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(),0),0);
   server.createContext("/upload",new MagaDrop.UploadHandler());server.createContext("/",new MagaDrop.PaginaHandler());
   server.setExecutor(Executors.newCachedThreadPool(r->{Thread t=new Thread(r);t.setDaemon(true);return t;}));server.start();
   try{
    URI base=URI.create("http://127.0.0.1:"+server.getAddress().getPort());HttpClient c=HttpClient.newHttpClient();
    check(c.send(HttpRequest.newBuilder(base.resolve("/")).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode()==200,"GET da página");
    HttpRequest sem=HttpRequest.newBuilder(base.resolve("/upload")).header("X-Filename","teste.txt").POST(HttpRequest.BodyPublishers.ofString("conteúdo")).build();
    check(c.send(sem,HttpResponse.BodyHandlers.ofString()).statusCode()==401,"upload sem código");
    enviar(c,base,"teste.txt");enviar(c,base,"teste.txt");
    check(Files.readString(temp.resolve("teste.txt")).equals("conteúdo"),"conteúdo salvo");check(Files.exists(temp.resolve("teste (1).txt")),"nome duplicado");
    HttpRequest trav=HttpRequest.newBuilder(base.resolve("/upload")).header("X-Filename","../fora.txt").header("X-Access-Code","123456").POST(HttpRequest.BodyPublishers.ofString("x")).build();
    check(c.send(trav,HttpResponse.BodyHandlers.ofString()).statusCode()==400,"travessia de diretório");
   }finally{server.stop(0);}System.out.println("Todos os testes do MagaDrop passaram.");
  }finally{try(var itens=Files.walk(temp)){itens.sorted(Comparator.reverseOrder()).forEach(p->{try{Files.deleteIfExists(p);}catch(Exception ignored){}});}}
 }
 static void enviar(HttpClient c,URI base,String nome)throws Exception{
  HttpRequest r=HttpRequest.newBuilder(base.resolve("/upload")).header("X-Filename",nome).header("X-Access-Code","123456").POST(HttpRequest.BodyPublishers.ofString("conteúdo",StandardCharsets.UTF_8)).build();
  check(c.send(r,HttpResponse.BodyHandlers.ofString()).statusCode()==201,"upload autorizado");
 }
 static void check(boolean ok,String caso){if(!ok)throw new AssertionError("Falhou: "+caso);}
}
