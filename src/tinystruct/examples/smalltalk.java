package tinystruct.examples;

import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Queue;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.tinystruct.AbstractApplication;
import org.tinystruct.ApplicationException;
import org.tinystruct.data.component.Builder;
import org.tinystruct.data.component.Builders;
import org.tinystruct.datatype.Variable;
import org.tinystruct.handle.Reforward;
import org.tinystruct.system.util.Matrix;
import org.tinystruct.system.util.StringUtilities;
import org.tinystruct.transfer.http.upload.ContentDisposition;
import org.tinystruct.transfer.http.upload.MultipartFormData;

public class smalltalk extends AbstractApplication {

  private static final long TIMEOUT = 1000;
  private volatile Map<String, Queue<Builder>> sessions;
  private final Map<String, Map<String, Queue<Builder>>> groups = Collections.synchronizedMap(new HashMap<String, Map<String, Queue<Builder>>>());

  @Override
  public void init() {
    this.setAction("talk", "index");
    this.setAction("talk/join", "join");
    this.setAction("talk/start", "start");
    this.setAction("talk/update", "update");
    this.setAction("talk/save", "save");
    this.setAction("talk/upload", "upload");
    this.setAction("talk/command", "command");
    this.setAction("talk/topic", "topic");
    this.setAction("talk/matrix", "matrix");
    this.setAction("talk/version", "version");

    this.setVariable("message", "");
    this.setVariable("topic", "");
  }

  public smalltalk index() {
    final HttpServletRequest request = (HttpServletRequest) this.context.getAttribute("HTTP_REQUEST");
    final HttpSession session = request.getSession();
    Object meeting_code = session.getAttribute("meeting_code");

    if ( meeting_code == null ) {
      meeting_code = java.util.UUID.randomUUID().toString();
      session.setAttribute("meeting_code", meeting_code);

      this.sessions = new HashMap<String, Queue<Builder>>();
      this.groups.put(meeting_code.toString(), this.sessions);

      System.out.println("New meeting generated:" + meeting_code);
    }

    this.setVariable("meeting_code", meeting_code.toString());
    Variable<?> topic;
    if ((topic = this.getVariable(meeting_code.toString())) != null) {
      this.setVariable("topic", topic.getValue().toString().replaceAll("[\r\n]", "<br />"), true);
    }
    
    return this;
  }

  public String matrix() throws ApplicationException {
    final HttpServletRequest request = (HttpServletRequest) this.context.getAttribute("HTTP_REQUEST");

    if (request.getParameter("meeting_code") != null) {
      BufferedImage qrImage = Matrix.toQRImage(this.getLink("talk/join") + "/" + request.getParameter("meeting_code"), 100, 100);
      return "data:image/png;base64," + Matrix.getBase64Image(qrImage);
    }

    return "";
  }

  public String join(String meeting_code) throws ApplicationException {
    if (groups.containsKey(meeting_code)) {
      final HttpServletRequest request = (HttpServletRequest) this.context.getAttribute("HTTP_REQUEST");
      final HttpServletResponse response = (HttpServletResponse) this.context.getAttribute("HTTP_RESPONSE");
      request.getSession().setAttribute("meeting_code", meeting_code);

      this.setVariable("meeting_code", meeting_code);

      Reforward reforward = new Reforward(request, response);
      reforward.setDefault("/?q=talk");
      reforward.forward();
    } else {
      return "Invalid meeting code.";
    }

    return "Please start the conversation with your name: "
        + this.config.get("default.base_url") + "talk/start/YOUR NAME";
  }

  public String start(String name) throws ApplicationException {
    final HttpServletRequest request = (HttpServletRequest) this.context.getAttribute("HTTP_REQUEST");
    final HttpServletResponse response = (HttpServletResponse) this.context.getAttribute("HTTP_RESPONSE");
    final HttpSession session = request.getSession();

    Object meeting_code = session.getAttribute("meeting_code");
    if (meeting_code == null) {
      Reforward reforward = new Reforward(request, response);
      reforward.setDefault("/?q=talk");
      reforward.forward();
    } else {
      this.setVariable("meeting_code", meeting_code.toString());
    }
    session.setAttribute("user", name);

    return name;
  }

  public String update() throws ApplicationException {
    final HttpServletRequest request = (HttpServletRequest) this.context.getAttribute("HTTP_REQUEST");
    final HttpSession session = request.getSession();
    final Object meeting_code = session.getAttribute("meeting_code");

    if ( meeting_code != null ) {
      this.checkup(meeting_code);

      Builder message;
      final String sessionId = session.getId();
      synchronized (this.sessions) {
        do {
          try {
            this.sessions.wait(TIMEOUT);
          } catch (InterruptedException e) {
            throw new ApplicationException(e.getMessage(), e);
          }
        } while(this.sessions.get(sessionId) == null || (message = this.sessions.get(sessionId).poll()) == null);

        System.out.println("["+sessionId+"][" + session.getAttribute("meeting_code") + "]:" + message);
        System.out.println("-------------");
        return message.toString();
      }
    }

    return "";
  }

  public String save() {
    final HttpServletRequest request = (HttpServletRequest) this.context.getAttribute("HTTP_REQUEST");
    final SimpleDateFormat format = new SimpleDateFormat("yyyy-M-d h:m:s");
    final HttpSession session = request.getSession();
    final Object meeting_code = session.getAttribute("meeting_code");

    if ( meeting_code != null ) {
      if (request.getParameter("text") != null && !request.getParameter("text").isEmpty()) {
        String[] agent = request.getHeader("User-Agent").split(" ");
        this.setVariable("browser", agent[agent.length - 1]);

        final Builder builder = new Builder();
        builder.put("user", session.getAttribute("user"));
        builder.put("time", format.format(new Date()));
        builder.put("message", filter(request.getParameter("text")));

        this.checkup(meeting_code);

        final String sessionId = session.getId();
        synchronized (this.sessions) {
          if (this.sessions.get(sessionId) == null) {
            this.sessions.put(sessionId, new ArrayDeque<Builder>());
          }

          final Collection<Queue<Builder>> set = this.sessions.values();
          final Iterator<Queue<Builder>> iterator = set.iterator();
          while(iterator.hasNext()) {
            iterator.next().add(builder);
            this.sessions.notifyAll();
          }
        }
        return builder.toString();
      }
    }

    return "{}";
  }

  public String command() {
    final HttpServletRequest request = (HttpServletRequest) this.context.getAttribute("HTTP_REQUEST");
    final HttpServletResponse response = (HttpServletResponse) this.context.getAttribute("HTTP_RESPONSE");
    final HttpSession session = request.getSession();
    final Object meeting_code = session.getAttribute("meeting_code");

    if ( meeting_code != null ) {
      response.setContentType("application/json");
      if (session.getAttribute("user") == null) {
        return "{ \"error\": \"missing user\" }";
      }

      Builder builder = new Builder();
      builder.put("user", session.getAttribute("user"));
      builder.put("cmd", request.getParameter("cmd"));

      this.checkup(meeting_code);

      final String sessionId = session.getId();
      synchronized (this.sessions) {
        if (this.sessions.get(sessionId) == null) {
          this.sessions.put(sessionId, new ArrayDeque<Builder>());
        }

        final Collection<Queue<Builder>> set = this.sessions.values();
        final Iterator<Queue<Builder>> iterator = set.iterator();
        while(iterator.hasNext()) {
          iterator.next().add(builder);
          this.sessions.notifyAll();
        }
      }

      return "{}";
    }

    return "{ \"error\": \"expired\" }";
  }

  public String upload() throws ApplicationException {
    final HttpServletRequest request = (HttpServletRequest) this.context.getAttribute("HTTP_REQUEST");
    final HttpServletResponse response = (HttpServletResponse) this.context.getAttribute("HTTP_RESPONSE");
    response.setContentType("text/html;charset=UTF-8");

    // Create path components to save the file
    final String path = this.context.getAttribute("system.directory") != null ? this.context.getAttribute("system.directory").toString() + "/files" : "files";

    final Builders builders = new Builders();
    try {
      final MultipartFormData iter = new MultipartFormData(request);
      ContentDisposition e = null;
      int read = 0;
      while ((e = iter.getNextPart()) != null) {
        final String fileName = e.getFileName();
        final Builder builder = new Builder();
        builder.put("type", StringUtilities.implode(";", Arrays.asList(e.getContentType())));
        builder.put("file", new StringBuffer().append(this.context.getAttribute("HTTP_SCHEME")).append("://").append(this.context.getAttribute("HTTP_SERVER")).append(":"+ request.getServerPort()).append( "/files/").append(fileName));
        final File f = new File(path + File.separator + fileName);
        if (!f.exists()) {
          if (!f.getParentFile().exists()) {
            f.getParentFile().mkdirs();
          }
        }

        final OutputStream out = new FileOutputStream(f);
        final BufferedOutputStream bout= new BufferedOutputStream(out);
        final ByteArrayInputStream is = new ByteArrayInputStream(e.getData());
        final BufferedInputStream bs = new BufferedInputStream(is);
        final byte[] bytes = new byte[8192];
        while ((read = bs.read(bytes)) != -1) {
           bout.write(bytes, 0, read);
        }
        bout.close();
        bs.close();

        builders.add(builder);
        System.out.println(String.format("File %s being uploaded to %s", new Object[] { fileName, path }));
      }
    } catch (IOException e) {
      throw new ApplicationException(e.getMessage(), e);
    } catch (ServletException e) {
      throw new ApplicationException(e.getMessage(), e);
    }

    return builders.toString();
  }

  public boolean topic() {
    final HttpServletRequest request = (HttpServletRequest) this.context.getAttribute("HTTP_REQUEST");
    final HttpSession session = request.getSession();
    final Object meeting_code = session.getAttribute("meeting_code");

    if ( meeting_code != null ) {
      this.setVariable(meeting_code.toString(), filter(request.getParameter("topic")));
      return true;
    }

    return false;
  }

  protected smalltalk exit() {
    final HttpServletRequest request = (HttpServletRequest) this.context.getAttribute("HTTP_REQUEST");
    request.getSession().removeAttribute("meeting_code");
    return this;
  }

  private void checkup(final Object meeting_code) {
    if ((this.sessions = this.groups.get(meeting_code)) == null) {
      this.sessions = new HashMap<String, Queue<Builder>>();
      this.groups.put(meeting_code.toString(), this.sessions);

      this.setVariable("meeting_code", meeting_code.toString());
    }
  }

  protected String filter(String text) {
    text = text.replaceAll("<script(.*)>(.*)<\\/script>", "");
    return text;
  }

  @Override
  public String version() {
    return "Welcome to use tinystruct 2.0";
  }
  
}
