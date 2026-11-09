using System;
using System.Collections.Generic;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;

// Relay RTSP local: aceita o player em 127.0.0.1:<porta> e repassa para a camera.
// - Injeta o cabecalho de autenticacao que o firmware das Yoosee aceita.
// - Corrige o "Transport" da resposta de SETUP (a camera diz RTP/AVP mas aceita TCP).
// - Manda TEARDOWN quando o cliente desconecta, para a camera liberar a sessao.
public class RtspRelay
{
    private readonly string rhost;
    private readonly int rport;
    private readonly int lport;
    private TcpListener listener;
    private volatile bool running = true;
    private static readonly Encoding Latin1 = Encoding.GetEncoding(28591);

    private volatile string sessionId;
    private volatile string lastUrl;
    private volatile bool wantTcp;

    public RtspRelay(string remoteHost, int remotePort, int localPort)
    {
        rhost = remoteHost;
        rport = remotePort;
        lport = localPort;
    }

    public void Start()
    {
        listener = new TcpListener(IPAddress.Loopback, lport);
        // permite reabrir a porta mesmo com a conexao anterior em TIME_WAIT
        try { listener.Server.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true); } catch { }
        listener.Start();
        var t = new Thread(AcceptLoop) { IsBackground = true };
        t.Start();
    }

    public void Stop()
    {
        running = false;
        try { listener.Stop(); } catch { }
    }

    // porta efetivamente aberta (util quando se pede 0 = automatica)
    public int Port
    {
        get
        {
            try { return ((IPEndPoint)listener.LocalEndpoint).Port; }
            catch { return 0; }
        }
    }

    private void AcceptLoop()
    {
        while (running)
        {
            try
            {
                var client = listener.AcceptTcpClient();
                sessionId = null;
                lastUrl = null;
                wantTcp = false;
                var t = new Thread(() => Handle(client)) { IsBackground = true };
                t.Start();
            }
            catch
            {
                if (!running) return;
            }
        }
    }

    private void Handle(TcpClient client)
    {
        var cam = new TcpClient();
        try { cam.Connect(rhost, rport); }
        catch { try { client.Close(); } catch { } return; }

        client.NoDelay = true;
        cam.NoDelay = true;

        var a = new Thread(() => Pump(client.GetStream(), cam.GetStream(), true)) { IsBackground = true };
        var b = new Thread(() => Pump(cam.GetStream(), client.GetStream(), false)) { IsBackground = true };
        a.Start();
        b.Start();
    }

    private void Pump(NetworkStream from, NetworkStream to, bool toServer)
    {
        var buf = new byte[65536];
        var acc = new List<byte>(16384);
        bool raw = false;
        int body = 0;

        try
        {
            while (true)
            {
                int n = from.Read(buf, 0, buf.Length);
                if (n <= 0) break;

                acc.AddRange(new ArraySegment<byte>(buf, 0, n));

                if (raw)
                {
                    to.Write(acc.ToArray(), 0, acc.Count);
                    to.Flush();
                    acc.Clear();
                    continue;
                }

                while (true)
                {
                    if (body > 0)
                    {
                        int take = Math.Min(body, acc.Count);
                        if (take <= 0) break;
                        to.Write(acc.GetRange(0, take).ToArray(), 0, take);
                        acc.RemoveRange(0, take);
                        body -= take;
                        to.Flush();
                        if (body > 0) break;
                    }

                    if (acc.Count > 0 && acc[0] == (byte)'$')
                    {
                        raw = true;
                        break;
                    }

                    int end = FindHeaderEnd(acc);
                    if (end <= 0) break;

                    var headerBytes = acc.GetRange(0, end).ToArray();
                    acc.RemoveRange(0, end);

                    string header = Latin1.GetString(headerBytes);
                    header = toServer ? RewriteRequest(header) : RewriteResponse(header);

                    body = ContentLength(header);
                    var outBytes = Latin1.GetBytes(header);
                    to.Write(outBytes, 0, outBytes.Length);
                    to.Flush();
                }
            }
        }
        catch { }

        if (toServer) { SendTeardown(to); }

        try { from.Close(); } catch { }
        try { to.Close(); } catch { }
    }

    // Avisa a camera que a sessao acabou; sem isso ela mantem a sessao pendurada
    private void SendTeardown(NetworkStream to)
    {
        try
        {
            if (string.IsNullOrEmpty(sessionId) || string.IsNullOrEmpty(lastUrl)) return;
            var sb = new StringBuilder();
            sb.Append("TEARDOWN ").Append(lastUrl).Append(" RTSP/1.0\r\n");
            sb.Append("CSeq: 9999\r\n");
            sb.Append("Session: ").Append(sessionId).Append("\r\n");
            sb.Append("Authorization: Digest username=\"admin\"\r\n\r\n");
            var b = Latin1.GetBytes(sb.ToString());
            to.Write(b, 0, b.Length);
            to.Flush();
        }
        catch { }
    }

    private static int ContentLength(string header)
    {
        var m = Regex.Match(header, "(?im)^Content-Length:\\s*(\\d+)");
        if (!m.Success) return 0;
        int v;
        return int.TryParse(m.Groups[1].Value, out v) ? v : 0;
    }

    private static int FindHeaderEnd(List<byte> b)
    {
        for (int i = 3; i < b.Count; i++)
        {
            if (b[i - 3] == 13 && b[i - 2] == 10 && b[i - 1] == 13 && b[i] == 10) return i + 1;
        }
        return -1;
    }

    private string RewriteRequest(string header)
    {
        header = header.Replace("rtsp://127.0.0.1:" + lport, "rtsp://" + rhost + ":" + rport);

        if (header.IndexOf("RTP/AVP/TCP", StringComparison.OrdinalIgnoreCase) >= 0) { wantTcp = true; }

        var linha = Regex.Match(header, "(?im)^(DESCRIBE|SETUP|PLAY|OPTIONS)\\s+(rtsp://[^\\s]+)\\s+RTSP/");
        if (linha.Success)
        {
            var metodo = linha.Groups[1].Value.ToUpperInvariant();
            if (metodo == "DESCRIBE" || string.IsNullOrEmpty(lastUrl)) { lastUrl = linha.Groups[2].Value; }
        }

        int idx = header.IndexOf("\r\n\r\n", StringComparison.Ordinal);
        if (idx < 0) return header;

        string head = header.Substring(0, idx);
        string tail = header.Substring(idx);

        head = Regex.Replace(head, "(?im)^Authorization:.*\r\n", "");
        head = Regex.Replace(head, "(?im)^Proxy-Authorization:.*\r\n", "");

        return head + "\r\nAuthorization: Digest username=\"admin\"" + tail;
    }

    private string RewriteResponse(string header)
    {
        var s = Regex.Match(header, "(?im)^Session:\\s*([^;\\r\\n]+)");
        if (s.Success) { sessionId = s.Groups[1].Value.Trim(); }

        // firmware responde "RTP/AVP" mesmo quando aceitou TCP interleaved
        if (wantTcp && header.IndexOf("Transport:", StringComparison.OrdinalIgnoreCase) >= 0)
        {
            header = Regex.Replace(header, "(?im)^Transport:\\s*RTP/AVP;", "Transport: RTP/AVP/TCP;");
        }

        return header.Replace("rtsp://" + rhost + ":" + rport, "rtsp://127.0.0.1:" + lport);
    }
}
