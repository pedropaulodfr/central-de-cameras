// Ptz.cs - Controle de movimento (PTZ) via ONVIF
//
// Compilado em runtime por CameraViewer.ps1 (Add-Type). C# 5 apenas.
// Funciona com cameras que expoem ONVIF (ex.: iCSee/Xiongmai na porta 8899).
// As Yoosee NAO expoem ONVIF: ver DECISOES.md.
//
// Uso: ConectarAsync() descobre o servico PTZ e o perfil; depois
// Mover(x, y) (velocidades -1..1) e Parar(). Os comandos saem por uma fila
// em thread propria: a interface nunca trava esperando a camera.

using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Net;
using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;

public class OnvifPtz
{
    public readonly string Host;
    public readonly int Porta;
    readonly string _usuario;
    readonly string _senha;

    // "inicial" | "conectando" | "pronto" | "indisponivel"
    public volatile string Estado = "inicial";
    public volatile string Erro = "";

    string _ptzUrl;
    string _perfil;

    readonly Queue<string> _fila = new Queue<string>();
    readonly object _trava = new object();
    Thread _worker;

    public OnvifPtz(string host, int porta, string usuario, string senha)
    {
        Host = host;
        Porta = porta;
        _usuario = usuario ?? "";
        _senha = senha ?? "";
    }

    // ---- conexao -----------------------------------------------------------
    public void ConectarAsync()
    {
        lock (_trava)
        {
            if (Estado == "conectando" || Estado == "pronto") return;
            Estado = "conectando";
            Erro = "";
        }
        ThreadPool.QueueUserWorkItem(delegate
        {
            try { Conectar(); Estado = "pronto"; }
            catch (Exception e) { Erro = e.Message; Estado = "indisponivel"; }
        });
    }

    string Base { get { return "http://" + Host + ":" + Porta.ToString(CultureInfo.InvariantCulture); } }

    // o aparelho pode informar um IP interno diferente: usamos sempre host:porta configurados
    string Reescrever(string url)
    {
        return Regex.Replace(url, "^http://[^/]+", Base);
    }

    void Conectar()
    {
        string cap = Post(Base + "/onvif/device_service",
            "<GetCapabilities xmlns=\"http://www.onvif.org/ver10/device/wsdl\"><Category>All</Category></GetCapabilities>");

        Match mp = Regex.Match(cap, "<(?:\\w+:)?PTZ>\\s*<(?:\\w+:)?XAddr>([^<]+)");
        Match mm = Regex.Match(cap, "<(?:\\w+:)?Media>\\s*<(?:\\w+:)?XAddr>([^<]+)");
        if (!mp.Success || !mm.Success) throw new Exception("A câmera não informa o serviço de movimento (PTZ).");
        _ptzUrl = Reescrever(mp.Groups[1].Value.Trim());
        string mediaUrl = Reescrever(mm.Groups[1].Value.Trim());

        string perfis = Post(mediaUrl, "<GetProfiles xmlns=\"http://www.onvif.org/ver10/media/wsdl\"/>");
        string token = null;
        foreach (Match m in Regex.Matches(perfis, "<(?:\\w+:)?Profiles[^>]*token=\"([^\"]+)\"[^>]*>(.*?)</(?:\\w+:)?Profiles>", RegexOptions.Singleline))
        {
            if (m.Groups[2].Value.IndexOf("PTZConfiguration", StringComparison.Ordinal) >= 0) { token = m.Groups[1].Value; break; }
        }
        if (token == null) throw new Exception("Esta câmera não tem motor de movimento (sem PTZ).");
        _perfil = token;
    }

    // ---- comandos ----------------------------------------------------------
    static string F(float v) { return v.ToString("0.00", CultureInfo.InvariantCulture); }

    public void Mover(float x, float y)
    {
        if (Estado != "pronto") return;
        string corpo =
            "<ContinuousMove xmlns=\"http://www.onvif.org/ver20/ptz/wsdl\">" +
            "<ProfileToken>" + _perfil + "</ProfileToken>" +
            "<Velocity><PanTilt x=\"" + F(x) + "\" y=\"" + F(y) + "\" xmlns=\"http://www.onvif.org/ver10/schema\"/></Velocity>" +
            "<Timeout>PT3S</Timeout>" + // se o Parar se perder, a camera para sozinha
            "</ContinuousMove>";
        lock (_trava) { if (_fila.Count >= 2) return; } // camera lenta: nao acumula repeticoes
        Enfileirar(corpo, false);
    }

    public void Parar()
    {
        if (Estado != "pronto") return;
        string corpo =
            "<Stop xmlns=\"http://www.onvif.org/ver20/ptz/wsdl\">" +
            "<ProfileToken>" + _perfil + "</ProfileToken>" +
            "<PanTilt>true</PanTilt><Zoom>true</Zoom></Stop>";
        Enfileirar(corpo, true); // descarta movimentos ainda nao enviados
    }

    void Enfileirar(string corpo, bool limpar)
    {
        lock (_trava)
        {
            if (limpar) _fila.Clear();
            _fila.Enqueue(corpo);
            if (_worker == null)
            {
                _worker = new Thread(Laco);
                _worker.IsBackground = true;
                _worker.Start();
            }
            Monitor.Pulse(_trava);
        }
    }

    void Laco()
    {
        while (true)
        {
            string corpo;
            lock (_trava)
            {
                while (_fila.Count == 0) Monitor.Wait(_trava);
                corpo = _fila.Dequeue();
            }
            // servidores HTTP embutidos (Yoosee) derrubam conexoes de vez em quando: reenvia
            Exception ultimo = null;
            for (int tentativa = 0; tentativa < 4; tentativa++)
            {
                try { Post(_ptzUrl, corpo); ultimo = null; break; }
                catch (Exception e) { ultimo = e; Thread.Sleep(150 + tentativa * 150); }
            }
            if (ultimo == null) Erro = ""; else Erro = ultimo.Message;
        }
    }

    // ---- SOAP com WS-Security (UsernameToken digest) ------------------------
    string Envelope(string corpo)
    {
        byte[] nonce = new byte[16];
        using (RNGCryptoServiceProvider rng = new RNGCryptoServiceProvider()) rng.GetBytes(nonce);
        string criado = DateTime.UtcNow.ToString("yyyy-MM-ddTHH:mm:ss.fffZ", CultureInfo.InvariantCulture);
        byte[] sufixo = Encoding.UTF8.GetBytes(criado + _senha);
        byte[] junto = new byte[nonce.Length + sufixo.Length];
        Buffer.BlockCopy(nonce, 0, junto, 0, nonce.Length);
        Buffer.BlockCopy(sufixo, 0, junto, nonce.Length, sufixo.Length);
        string digest;
        using (SHA1 sha = SHA1.Create()) digest = Convert.ToBase64String(sha.ComputeHash(junto));

        return "<?xml version=\"1.0\"?>" +
            "<s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\"><s:Header>" +
            "<Security s:mustUnderstand=\"1\" xmlns=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd\">" +
            "<UsernameToken><Username>" + Escapar(_usuario) + "</Username>" +
            "<Password Type=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordDigest\">" + digest + "</Password>" +
            "<Nonce EncodingType=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary\">" + Convert.ToBase64String(nonce) + "</Nonce>" +
            "<Created xmlns=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd\">" + criado + "</Created>" +
            "</UsernameToken></Security></s:Header><s:Body>" + corpo + "</s:Body></s:Envelope>";
    }

    static string Escapar(string s)
    {
        return s.Replace("&", "&amp;").Replace("<", "&lt;").Replace(">", "&gt;");
    }

    string Post(string url, string corpo)
    {
        HttpWebRequest rq = (HttpWebRequest)WebRequest.Create(url);
        rq.Method = "POST";
        rq.ContentType = "application/soap+xml; charset=utf-8";
        rq.Timeout = 5000;
        rq.ReadWriteTimeout = 5000;
        rq.Proxy = null;
        rq.KeepAlive = false;                       // servidores HTTP embutidos (Yoosee) fecham a conexao
        rq.ServicePoint.Expect100Continue = false;  // e nao entendem "Expect: 100-continue"
        byte[] dados = Encoding.UTF8.GetBytes(Envelope(corpo));
        rq.ContentLength = dados.Length;
        using (Stream s = rq.GetRequestStream()) s.Write(dados, 0, dados.Length);

        string resp;
        try
        {
            using (WebResponse r = rq.GetResponse())
            using (StreamReader sr = new StreamReader(r.GetResponseStream(), Encoding.UTF8)) resp = sr.ReadToEnd();
        }
        catch (WebException ex)
        {
            if (ex.Response == null) throw new Exception("Sem resposta da câmera (" + ex.Message + ").");
            using (StreamReader sr = new StreamReader(ex.Response.GetResponseStream(), Encoding.UTF8)) resp = sr.ReadToEnd();
        }

        if (Regex.IsMatch(resp, "<(?:\\w+:)?Fault[ >]"))
        {
            Match m = Regex.Match(resp, "<(?:\\w+:)?Text[^>]*>([^<]+)");
            string motivo = m.Success ? m.Groups[1].Value : "a câmera recusou o comando";
            if (motivo.IndexOf("auth", StringComparison.OrdinalIgnoreCase) >= 0 || motivo.IndexOf("Sender", StringComparison.OrdinalIgnoreCase) >= 0)
                motivo = "usuário/senha recusados pela câmera (" + motivo + ")";
            throw new Exception(motivo);
        }
        return resp;
    }
}
