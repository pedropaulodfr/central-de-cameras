// Ui.cs - Design system da Central de Cameras
//
// Compilado em runtime por CameraViewer.ps1 (Add-Type). C# 5 apenas.
// Contem: tokens (Tema), icones vetoriais (Icones) e componentes
// (BotaoUi, Pilula, Cartao, CampoUi, TemaRenderer). Nada de cor solta nas telas:
// o PowerShell le tudo daqui.

using System;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Text;
using System.Runtime.InteropServices;
using System.Windows.Forms;

// ---------------------------------------------------------------------------
// Tokens
// ---------------------------------------------------------------------------
public static class Tema
{
    // superficies (do mais fundo ao mais elevado)
    public static readonly Color Fundo        = Color.FromArgb(11, 15, 20);    // #0B0F14
    public static readonly Color Superficie   = Color.FromArgb(18, 24, 32);    // #121820
    public static readonly Color Elevado      = Color.FromArgb(25, 34, 45);    // #19222D
    public static readonly Color Hover        = Color.FromArgb(34, 46, 60);    // #222E3C
    public static readonly Color Borda        = Color.FromArgb(40, 53, 67);    // #283543
    public static readonly Color BordaForte   = Color.FromArgb(66, 82, 100);   // #425264

    // texto (>= 4,5:1 sobre Superficie)
    public static readonly Color Texto        = Color.FromArgb(230, 237, 245); // #E6EDF5
    public static readonly Color TextoSuave   = Color.FromArgb(150, 167, 187); // #96A7BB
    public static readonly Color TextoOff     = Color.FromArgb(105, 120, 138); // desabilitado

    // acao primaria (texto branco >= 4,5:1)
    public static readonly Color Primario       = Color.FromArgb(37, 99, 235);  // #2563EB
    public static readonly Color PrimarioHover  = Color.FromArgb(31, 87, 214);
    public static readonly Color PrimarioPress  = Color.FromArgb(27, 77, 191);
    public static readonly Color AcentoClaro    = Color.FromArgb(96, 165, 250); // foco, links

    // acao destrutiva / gravando
    public static readonly Color Perigo        = Color.FromArgb(211, 34, 34);
    public static readonly Color PerigoHover   = Color.FromArgb(190, 29, 29);
    public static readonly Color PerigoPress   = Color.FromArgb(169, 24, 24);

    // semanticas (como texto/ponto sobre fundo escuro)
    public static readonly Color Sucesso = Color.FromArgb(52, 211, 153);   // #34D399
    public static readonly Color Alerta  = Color.FromArgb(245, 184, 75);   // #F5B84B
    public static readonly Color Erro    = Color.FromArgb(248, 113, 113);  // #F87171
    public static readonly Color Info    = AcentoClaro;

    // raios
    public const float RaioBotao = 8f;
    public const float RaioCampo = 8f;

    public static Font Fonte(float pt, bool negrito)
    {
        return new Font(negrito ? "Segoe UI Semibold" : "Segoe UI", pt, FontStyle.Regular, GraphicsUnit.Point);
    }

    public static GraphicsPath Retangulo(RectangleF r, float raio)
    {
        float d = Math.Min(raio * 2f, Math.Min(r.Width, r.Height));
        GraphicsPath p = new GraphicsPath();
        if (d <= 0.5f) { p.AddRectangle(r); return p; }
        p.AddArc(r.X, r.Y, d, d, 180, 90);
        p.AddArc(r.Right - d, r.Y, d, d, 270, 90);
        p.AddArc(r.Right - d, r.Bottom - d, d, d, 0, 90);
        p.AddArc(r.X, r.Bottom - d, d, d, 90, 90);
        p.CloseFigure();
        return p;
    }

    // ---- barra de titulo escura (Windows 10 20H1+ / 11) ----
    [DllImport("dwmapi.dll")]
    static extern int DwmSetWindowAttribute(IntPtr hwnd, int attr, ref int valor, int tamanho);

    static int Bgr(Color c) { return c.R | (c.G << 8) | (c.B << 16); }

    public static void EscurecerBarra(Form f)
    {
        f.HandleCreated += delegate { AplicarBarra(f); };
        if (f.IsHandleCreated) AplicarBarra(f);
    }

    static void AplicarBarra(Form f)
    {
        try
        {
            int um = 1;
            DwmSetWindowAttribute(f.Handle, 20, ref um, 4);
            DwmSetWindowAttribute(f.Handle, 19, ref um, 4);
            int cap = Bgr(Superficie);
            DwmSetWindowAttribute(f.Handle, 35, ref cap, 4);
            int tx = Bgr(Texto);
            DwmSetWindowAttribute(f.Handle, 36, ref tx, 4);
        }
        catch { }
    }

    // ---- tooltip escura ----
    public static void EstilizarDica(ToolTip t)
    {
        Font f = Fonte(9.5f, false);
        t.OwnerDraw = true;
        t.Popup += delegate(object s, PopupEventArgs e)
        {
            string txt = e.AssociatedControl != null ? t.GetToolTip(e.AssociatedControl) : "";
            Size m = TextRenderer.MeasureText(txt, f);
            e.ToolTipSize = new Size(m.Width + 20, m.Height + 14);
        };
        t.Draw += delegate(object s, DrawToolTipEventArgs e)
        {
            using (SolidBrush b = new SolidBrush(Elevado)) e.Graphics.FillRectangle(b, e.Bounds);
            using (Pen p = new Pen(BordaForte)) e.Graphics.DrawRectangle(p, 0, 0, e.Bounds.Width - 1, e.Bounds.Height - 1);
            TextRenderer.DrawText(e.Graphics, e.ToolTipText, f, e.Bounds, Texto,
                TextFormatFlags.HorizontalCenter | TextFormatFlags.VerticalCenter | TextFormatFlags.NoPadding);
        };
    }
}

// ---------------------------------------------------------------------------
// Icones vetoriais (grade 24x24, traco 2px, pontas arredondadas)
// ---------------------------------------------------------------------------
public static class Icones
{
    static void L(Graphics g, Pen p, float x1, float y1, float x2, float y2) { g.DrawLine(p, x1, y1, x2, y2); }

    static void Linhas(Graphics g, Pen p, params float[] v)
    {
        PointF[] pts = new PointF[v.Length / 2];
        for (int i = 0; i < pts.Length; i++) pts[i] = new PointF(v[i * 2], v[i * 2 + 1]);
        g.DrawLines(p, pts);
    }

    static void Poligono(Graphics g, Pen p, params float[] v)
    {
        PointF[] pts = new PointF[v.Length / 2];
        for (int i = 0; i < pts.Length; i++) pts[i] = new PointF(v[i * 2], v[i * 2 + 1]);
        g.DrawPolygon(p, pts);
    }

    static void Alto(Graphics g, Pen p)
    {
        Poligono(g, p, 4, 9.5f, 8, 9.5f, 13, 5.5f, 13, 18.5f, 8, 14.5f, 4, 14.5f);
    }

    public static void Desenhar(Graphics g, string nome, RectangleF r, Color cor)
    {
        GraphicsState st = g.Save();
        g.SmoothingMode = SmoothingMode.AntiAlias;
        g.TranslateTransform(r.X, r.Y);
        float s = Math.Min(r.Width, r.Height) / 24f;
        g.ScaleTransform(s, s);
        using (Pen p = new Pen(cor, 2f))
        using (SolidBrush b = new SolidBrush(cor))
        {
            p.StartCap = LineCap.Round; p.EndCap = LineCap.Round; p.LineJoin = LineJoin.Round;
            switch (nome)
            {
                case "gravar":
                    g.FillEllipse(b, 6f, 6f, 12f, 12f);
                    break;
                case "parar":
                    using (GraphicsPath gp = Tema.Retangulo(new RectangleF(6.5f, 6.5f, 11f, 11f), 2f)) g.FillPath(b, gp);
                    break;
                case "foto":
                    using (GraphicsPath gp = Tema.Retangulo(new RectangleF(3f, 7f, 18f, 12f), 2.5f)) g.DrawPath(p, gp);
                    Linhas(g, p, 8, 7, 9.5f, 4.5f, 14.5f, 4.5f, 16, 7);
                    g.DrawEllipse(p, 8.8f, 9.8f, 6.4f, 6.4f);
                    break;
                case "reconectar":
                {
                    g.DrawArc(p, 5f, 5f, 14f, 14f, 30f, 280f);
                    double a = 310.0 * Math.PI / 180.0;
                    float ex = 12f + 7f * (float)Math.Cos(a), ey = 12f + 7f * (float)Math.Sin(a);
                    float tx = -(float)Math.Sin(a), ty = (float)Math.Cos(a);
                    float nx = (float)Math.Cos(a), ny = (float)Math.Sin(a);
                    L(g, p, ex, ey, ex - tx * 4f + nx * 3.2f, ey - ty * 4f + ny * 3.2f);
                    L(g, p, ex, ey, ex - tx * 4f - nx * 3.2f, ey - ty * 4f - ny * 3.2f);
                    break;
                }
                case "renomear":
                    Poligono(g, p, 4, 20, 5.5f, 14.5f, 15.5f, 4.5f, 19.5f, 8.5f, 9.5f, 18.5f);
                    L(g, p, 13, 7, 17, 11);
                    break;
                case "pasta":
                    Poligono(g, p, 3, 6, 9.5f, 6, 11.5f, 8.5f, 21, 8.5f, 21, 19, 3, 19);
                    break;
                case "telacheia":
                    Linhas(g, p, 4, 9, 4, 4, 9, 4);
                    Linhas(g, p, 15, 4, 20, 4, 20, 9);
                    Linhas(g, p, 20, 15, 20, 20, 15, 20);
                    Linhas(g, p, 9, 20, 4, 20, 4, 15);
                    break;
                case "sairtelacheia":
                    Linhas(g, p, 9, 4, 9, 9, 4, 9);
                    Linhas(g, p, 15, 4, 15, 9, 20, 9);
                    Linhas(g, p, 20, 15, 15, 15, 15, 20);
                    Linhas(g, p, 4, 15, 9, 15, 9, 20);
                    break;
                case "ampliar":
                    Linhas(g, p, 10, 5, 5, 5, 5, 19, 19, 19, 19, 14);
                    Linhas(g, p, 14, 4, 20, 4, 20, 10);
                    L(g, p, 20, 4, 12.5f, 11.5f);
                    break;
                case "reduzir":
                    Linhas(g, p, 14, 4, 14, 10, 20, 10);
                    L(g, p, 14, 10, 21, 3);
                    Linhas(g, p, 10, 20, 10, 14, 4, 14);
                    L(g, p, 10, 14, 3, 21);
                    break;
                case "som":
                    Alto(g, p);
                    g.DrawArc(p, 10f, 8f, 7f, 8f, -60f, 120f);
                    g.DrawArc(p, 9f, 5f, 12f, 14f, -60f, 120f);
                    break;
                case "mudo":
                    Alto(g, p);
                    L(g, p, 16.5f, 9.5f, 21.5f, 14.5f);
                    L(g, p, 21.5f, 9.5f, 16.5f, 14.5f);
                    break;
                case "mover":
                    L(g, p, 12, 3, 12, 21);
                    L(g, p, 3, 12, 21, 12);
                    Linhas(g, p, 9, 6, 12, 3, 15, 6);
                    Linhas(g, p, 9, 18, 12, 21, 15, 18);
                    Linhas(g, p, 6, 9, 3, 12, 6, 15);
                    Linhas(g, p, 18, 9, 21, 12, 18, 15);
                    break;
                case "reordenar":
                    L(g, p, 4, 8, 19, 8);
                    Linhas(g, p, 15.5f, 4.5f, 19.5f, 8, 15.5f, 11.5f);
                    L(g, p, 20, 16, 5, 16);
                    Linhas(g, p, 8.5f, 12.5f, 4.5f, 16, 8.5f, 19.5f);
                    break;
                case "virar":
                    g.FillEllipse(b, 10.4f, 10.4f, 3.2f, 3.2f);
                    Linhas(g, p, 9, 7, 12, 4, 15, 7);
                    Linhas(g, p, 9, 17, 12, 20, 15, 17);
                    Linhas(g, p, 7, 9, 4, 12, 7, 15);
                    Linhas(g, p, 17, 9, 20, 12, 17, 15);
                    break;
                case "camera":
                    using (GraphicsPath gp = Tema.Retangulo(new RectangleF(2.5f, 6.5f, 13f, 11f), 2.5f)) g.DrawPath(p, gp);
                    Poligono(g, p, 15.5f, 10.5f, 21, 7.5f, 21, 16.5f, 15.5f, 13.5f);
                    break;
            }
        }
        g.Restore(st);
    }
}

// ---------------------------------------------------------------------------
// Botao: primario | secundario | fantasma | perigo. Icone vetorial opcional.
// ---------------------------------------------------------------------------
public class BotaoUi : Button
{
    string _variante = "secundario";
    string _icone = "";
    bool _soIcone;
    Color _corIcone = Color.Empty;
    bool _sobre, _premido;

    const int TamIcone = 18;

    public BotaoUi()
    {
        SetStyle(ControlStyles.UserPaint | ControlStyles.AllPaintingInWmPaint |
                 ControlStyles.OptimizedDoubleBuffer | ControlStyles.ResizeRedraw, true);
        FlatStyle = FlatStyle.Flat;
        FlatAppearance.BorderSize = 0;
        UseVisualStyleBackColor = false;
        TabStop = true;
        Cursor = Cursors.Hand;
        Font = Tema.Fonte(10f, true);
        Height = 44;
        AutoSizeMode = AutoSizeMode.GrowAndShrink;
    }

    public string Variante { get { return _variante; } set { _variante = value; Invalidate(); } }
    public string Icone    { get { return _icone; }    set { _icone = value ?? ""; Reajustar(); } }
    public bool SoIcone    { get { return _soIcone; }  set { _soIcone = value; Reajustar(); } }
    public Color CorIcone  { get { return _corIcone; } set { _corIcone = value; Invalidate(); } }

    void Reajustar()
    {
        if (AutoSize) Size = GetPreferredSize(Size.Empty);
        Invalidate();
    }

    int LarguraTexto()
    {
        if (_soIcone || string.IsNullOrEmpty(Text)) return 0;
        return TextRenderer.MeasureText(Text, Font, new Size(int.MaxValue, int.MaxValue),
            TextFormatFlags.NoPadding | TextFormatFlags.SingleLine).Width + 2;
    }

    public override Size GetPreferredSize(Size proposedSize)
    {
        if (_soIcone) return new Size(Math.Max(Height, 44), Height);
        int w = 32 + LarguraTexto();
        if (_icone.Length > 0) { w += TamIcone; if (LarguraTexto() > 0) w += 8; }
        return new Size(Math.Max(w, 44), Height);
    }

    protected override void OnTextChanged(EventArgs e) { base.OnTextChanged(e); Reajustar(); }
    protected override void OnMouseEnter(EventArgs e) { _sobre = true; Invalidate(); base.OnMouseEnter(e); }
    protected override void OnMouseLeave(EventArgs e) { _sobre = false; _premido = false; Invalidate(); base.OnMouseLeave(e); }
    protected override void OnMouseDown(MouseEventArgs e) { if (e.Button == MouseButtons.Left) { _premido = true; Invalidate(); } base.OnMouseDown(e); }
    protected override void OnMouseUp(MouseEventArgs e) { _premido = false; Invalidate(); base.OnMouseUp(e); }
    protected override void OnKeyDown(KeyEventArgs e) { if (e.KeyCode == Keys.Space) { _premido = true; Invalidate(); } base.OnKeyDown(e); }
    protected override void OnKeyUp(KeyEventArgs e) { _premido = false; Invalidate(); base.OnKeyUp(e); }
    protected override void OnGotFocus(EventArgs e) { Invalidate(); base.OnGotFocus(e); }
    protected override void OnLostFocus(EventArgs e) { Invalidate(); base.OnLostFocus(e); }
    protected override void OnEnabledChanged(EventArgs e) { Invalidate(); base.OnEnabledChanged(e); }

    void Cores(out Color fundo, out Color borda, out Color texto)
    {
        bool ativo = Enabled;
        fundo = Color.Transparent; borda = Color.Transparent; texto = Tema.Texto;
        if (!ativo)
        {
            texto = Tema.TextoOff;
            if (_variante == "primario" || _variante == "perigo" || _variante == "secundario") fundo = Tema.Elevado;
            return;
        }
        switch (_variante)
        {
            case "primario":
                fundo = _premido ? Tema.PrimarioPress : (_sobre ? Tema.PrimarioHover : Tema.Primario);
                texto = Color.White;
                break;
            case "perigo":
                fundo = _premido ? Tema.PerigoPress : (_sobre ? Tema.PerigoHover : Tema.Perigo);
                texto = Color.White;
                break;
            case "fantasma":
                if (_premido) fundo = Tema.Borda; else if (_sobre) fundo = Tema.Hover;
                break;
            default:
                fundo = _premido ? Tema.Borda : (_sobre ? Tema.Hover : Tema.Elevado);
                borda = _sobre ? Tema.BordaForte : Tema.Borda;
                break;
        }
    }

    protected override void OnPaint(PaintEventArgs e)
    {
        Graphics g = e.Graphics;
        g.SmoothingMode = SmoothingMode.AntiAlias;
        g.TextRenderingHint = TextRenderingHint.ClearTypeGridFit;
        g.Clear(Parent != null ? Parent.BackColor : Tema.Fundo);

        Color fundo, borda, texto;
        Cores(out fundo, out borda, out texto);

        RectangleF rc = new RectangleF(0.5f, 0.5f, Width - 1f, Height - 1f);
        using (GraphicsPath path = Tema.Retangulo(rc, Tema.RaioBotao))
        {
            if (fundo.A > 0) using (SolidBrush b = new SolidBrush(fundo)) g.FillPath(b, path);
            if (borda.A > 0) using (Pen p = new Pen(borda, 1f)) g.DrawPath(p, path);
        }

        int lt = LarguraTexto();
        bool temIcone = _icone.Length > 0;
        int total = (temIcone ? TamIcone : 0) + ((temIcone && lt > 0) ? 8 : 0) + lt;
        int x = (Width - total) / 2;
        if (temIcone)
        {
            Color ci = (Enabled && !_corIcone.IsEmpty) ? _corIcone : texto;
            Icones.Desenhar(g, _icone, new RectangleF(x, (Height - TamIcone) / 2f, TamIcone, TamIcone), ci);
            x += TamIcone + (lt > 0 ? 8 : 0);
        }
        if (lt > 0)
        {
            TextRenderer.DrawText(g, Text, Font, new Rectangle(x, 0, lt, Height), texto,
                TextFormatFlags.VerticalCenter | TextFormatFlags.Left | TextFormatFlags.NoPadding |
                TextFormatFlags.SingleLine | TextFormatFlags.EndEllipsis);
        }

        if (Focused && ShowFocusCues)
        {
            using (Pen p = new Pen(Tema.AcentoClaro, 2f))
            using (GraphicsPath pf = Tema.Retangulo(new RectangleF(1f, 1f, Width - 2f, Height - 2f), Tema.RaioBotao))
                g.DrawPath(p, pf);
        }
    }
}

// ---------------------------------------------------------------------------
// Pilula de estado: ponto colorido + texto (nunca so cor)
// ---------------------------------------------------------------------------
public class Pilula : Control
{
    Color _cor = Tema.TextoSuave;

    public Pilula()
    {
        SetStyle(ControlStyles.UserPaint | ControlStyles.AllPaintingInWmPaint |
                 ControlStyles.OptimizedDoubleBuffer | ControlStyles.ResizeRedraw, true);
        TabStop = false;
        Font = Tema.Fonte(9f, true);
    }

    public Color CorPonto { get { return _cor; } set { if (_cor != value) { _cor = value; Invalidate(); } } }

    protected override void OnTextChanged(EventArgs e) { base.OnTextChanged(e); Invalidate(); }

    protected override void OnPaint(PaintEventArgs e)
    {
        Graphics g = e.Graphics;
        g.SmoothingMode = SmoothingMode.AntiAlias;
        g.TextRenderingHint = TextRenderingHint.ClearTypeGridFit;
        g.Clear(Parent != null ? Parent.BackColor : Tema.Fundo);
        if (string.IsNullOrEmpty(Text)) return;

        int tw = TextRenderer.MeasureText(Text, Font, new Size(int.MaxValue, int.MaxValue),
            TextFormatFlags.NoPadding | TextFormatFlags.SingleLine).Width + 2;
        float w = 12 + 8 + 6 + tw + 12;
        float h = 28;
        float x = Width - w - 8;
        float y = (Height - h) / 2f;
        RectangleF rc = new RectangleF(x, y, w, h);
        using (GraphicsPath path = Tema.Retangulo(rc, h / 2f))
        {
            using (SolidBrush b = new SolidBrush(Color.FromArgb(34, _cor))) g.FillPath(b, path);
            using (Pen p = new Pen(Color.FromArgb(110, _cor), 1f)) g.DrawPath(p, path);
        }
        using (SolidBrush b = new SolidBrush(_cor)) g.FillEllipse(b, x + 12, y + (h - 8) / 2f, 8, 8);
        TextRenderer.DrawText(g, Text, Font, new Rectangle((int)(x + 26), (int)y, tw, (int)h), Tema.Texto,
            TextFormatFlags.VerticalCenter | TextFormatFlags.Left | TextFormatFlags.NoPadding | TextFormatFlags.SingleLine);
    }
}

// ---------------------------------------------------------------------------
// Cartao: painel com borda de cor/espessura variavel (borda sempre visivel)
// ---------------------------------------------------------------------------
public class Cartao : Panel
{
    Color _borda = Tema.Borda;
    int _esp = 1;

    public Cartao()
    {
        DoubleBuffered = true;
        Padding = new Padding(2);
        BackColor = Tema.Fundo;
    }

    public Color CorBorda { get { return _borda; } set { if (_borda != value) { _borda = value; Invalidate(); } } }
    public int Espessura  { get { return _esp; }   set { if (_esp != value) { _esp = value; Invalidate(); } } }

    protected override void OnPaint(PaintEventArgs e)
    {
        base.OnPaint(e);
        using (Pen p = new Pen(_borda, _esp))
            e.Graphics.DrawRectangle(p, _esp / 2f, _esp / 2f, Width - _esp, Height - _esp);
    }
}

// ---------------------------------------------------------------------------
// Campo de texto com borda, foco e estado de erro
// ---------------------------------------------------------------------------
public class CampoUi : Panel
{
    public TextBox Caixa;
    bool _erro;

    public CampoUi()
    {
        SetStyle(ControlStyles.UserPaint | ControlStyles.AllPaintingInWmPaint |
                 ControlStyles.OptimizedDoubleBuffer | ControlStyles.ResizeRedraw, true);
        Height = 44;
        Caixa = new TextBox();
        Caixa.BorderStyle = BorderStyle.None;
        Caixa.BackColor = Tema.Elevado;
        Caixa.ForeColor = Tema.Texto;
        Caixa.Font = Tema.Fonte(10.5f, false);
        Caixa.GotFocus += delegate { Invalidate(); };
        Caixa.LostFocus += delegate { Invalidate(); };
        Controls.Add(Caixa);
        Centralizar();
    }

    public bool Erro { get { return _erro; } set { if (_erro != value) { _erro = value; Invalidate(); } } }

    void Centralizar()
    {
        if (Caixa == null) return; // resize do construtor, antes da caixa existir
        Caixa.Left = 12;
        Caixa.Width = Math.Max(10, Width - 24);
        Caixa.Top = Math.Max(0, (Height - Caixa.Height) / 2);
    }

    protected override void OnResize(EventArgs e) { base.OnResize(e); Centralizar(); }
    protected override void OnMouseDown(MouseEventArgs e) { Caixa.Focus(); base.OnMouseDown(e); }

    protected override void OnPaint(PaintEventArgs e)
    {
        Graphics g = e.Graphics;
        g.SmoothingMode = SmoothingMode.AntiAlias;
        g.Clear(Parent != null ? Parent.BackColor : Tema.Superficie);
        Color borda = _erro ? Tema.Erro : (Caixa.Focused ? Tema.AcentoClaro : Tema.Borda);
        float esp = (_erro || Caixa.Focused) ? 2f : 1f;
        RectangleF rc = new RectangleF(esp / 2f, esp / 2f, Width - esp, Height - esp);
        using (GraphicsPath path = Tema.Retangulo(rc, Tema.RaioCampo))
        {
            using (SolidBrush b = new SolidBrush(Tema.Elevado)) g.FillPath(b, path);
            using (Pen p = new Pen(borda, esp)) g.DrawPath(p, path);
        }
    }
}

// ---------------------------------------------------------------------------
// D-pad: cruz direcional (cima/baixo/esq/dir) + centro (parar).
// Segure para mover: Pressionado(direcao) ao apertar, Solto() ao soltar.
// Direcoes: "cima" "baixo" "esq" "dir" "centro". Setas do teclado tambem funcionam.
// ---------------------------------------------------------------------------
public class DPad : Control
{
    string _sobre;      // setor sob o mouse
    string _premido;    // setor pressionado (mouse ou teclado)
    bool _porTeclado;

    public event Action<string> Pressionado;
    public event Action Solto;

    public DPad()
    {
        SetStyle(ControlStyles.UserPaint | ControlStyles.AllPaintingInWmPaint |
                 ControlStyles.OptimizedDoubleBuffer | ControlStyles.ResizeRedraw |
                 ControlStyles.Selectable, true);
        TabStop = true;
        Size = new Size(176, 176);
        Cursor = Cursors.Hand;
    }

    float Raio { get { return Math.Min(Width, Height) / 2f - 3f; } }
    float RaioCentro { get { return Raio * 0.30f; } }
    PointF Centro { get { return new PointF(Width / 2f, Height / 2f); } }

    string Setor(Point pt)
    {
        float dx = pt.X - Centro.X, dy = pt.Y - Centro.Y;
        double d = Math.Sqrt(dx * dx + dy * dy);
        if (d > Raio) return null;
        if (d <= RaioCentro) return "centro";
        double ang = Math.Atan2(dy, dx) * 180.0 / Math.PI; // 0=direita, 90=baixo
        if (ang >= -45 && ang < 45) return "dir";
        if (ang >= 45 && ang < 135) return "baixo";
        if (ang >= -135 && ang < -45) return "cima";
        return "esq";
    }

    void Apertar(string s)
    {
        if (!Enabled || s == null || _premido != null) return;
        _premido = s;
        Invalidate();
        if (Pressionado != null) Pressionado(s);
    }

    void Soltar()
    {
        if (_premido == null) return;
        _premido = null;
        _porTeclado = false;
        Invalidate();
        if (Solto != null) Solto();
    }

    protected override void OnMouseMove(MouseEventArgs e)
    {
        string s = Setor(e.Location);
        if (s != _sobre) { _sobre = s; Invalidate(); }
        base.OnMouseMove(e);
    }
    protected override void OnMouseLeave(EventArgs e) { _sobre = null; Invalidate(); base.OnMouseLeave(e); }
    protected override void OnMouseDown(MouseEventArgs e)
    {
        Focus();
        if (e.Button == MouseButtons.Left) { Capture = true; Apertar(Setor(e.Location)); }
        base.OnMouseDown(e);
    }
    protected override void OnMouseUp(MouseEventArgs e)
    {
        Capture = false;
        if (!_porTeclado) Soltar();
        base.OnMouseUp(e);
    }

    static string Tecla(Keys k)
    {
        switch (k)
        {
            case Keys.Up: return "cima";
            case Keys.Down: return "baixo";
            case Keys.Left: return "esq";
            case Keys.Right: return "dir";
            case Keys.Space: return "centro";
        }
        return null;
    }

    protected override bool IsInputKey(Keys keyData)
    {
        return Tecla(keyData & Keys.KeyCode) != null || base.IsInputKey(keyData);
    }
    protected override void OnKeyDown(KeyEventArgs e)
    {
        string s = Tecla(e.KeyCode);
        if (s != null)
        {
            e.Handled = true;
            if (_premido == null) { _porTeclado = true; Apertar(s); } // ignora auto-repeticao
            return;
        }
        base.OnKeyDown(e);
    }
    protected override void OnKeyUp(KeyEventArgs e)
    {
        if (Tecla(e.KeyCode) != null) { e.Handled = true; if (_porTeclado) Soltar(); return; }
        base.OnKeyUp(e);
    }
    protected override void OnLostFocus(EventArgs e) { Soltar(); Invalidate(); base.OnLostFocus(e); }
    protected override void OnGotFocus(EventArgs e) { Invalidate(); base.OnGotFocus(e); }
    protected override void OnEnabledChanged(EventArgs e) { if (!Enabled) Soltar(); Invalidate(); base.OnEnabledChanged(e); }

    GraphicsPath Fatia(float inicio, float varrer)
    {
        float R = Raio, r = RaioCentro + 3f;
        PointF c = Centro;
        GraphicsPath p = new GraphicsPath();
        p.AddArc(c.X - R, c.Y - R, R * 2, R * 2, inicio, varrer);
        p.AddArc(c.X - r, c.Y - r, r * 2, r * 2, inicio + varrer, -varrer);
        p.CloseFigure();
        return p;
    }

    void Chevron(Graphics g, string dir, Color cor)
    {
        float meio = (Raio + RaioCentro) / 2f + 2f;
        float dx = 0, dy = 0;
        switch (dir) { case "cima": dy = -1; break; case "baixo": dy = 1; break; case "esq": dx = -1; break; default: dx = 1; break; }
        PointF c = new PointF(Centro.X + dx * meio, Centro.Y + dy * meio);
        float a = 7f; // meia-largura
        PointF[] pts;
        if (dy != 0) pts = new PointF[] { new PointF(c.X - a, c.Y + dy * a * -0.5f), new PointF(c.X, c.Y + dy * a * 0.5f), new PointF(c.X + a, c.Y + dy * a * -0.5f) };
        else pts = new PointF[] { new PointF(c.X + dx * a * -0.5f, c.Y - a), new PointF(c.X + dx * a * 0.5f, c.Y), new PointF(c.X + dx * a * -0.5f, c.Y + a) };
        using (Pen p = new Pen(cor, 3f))
        {
            p.StartCap = LineCap.Round; p.EndCap = LineCap.Round; p.LineJoin = LineJoin.Round;
            g.DrawLines(p, pts);
        }
    }

    protected override void OnPaint(PaintEventArgs e)
    {
        Graphics g = e.Graphics;
        g.SmoothingMode = SmoothingMode.AntiAlias;
        g.Clear(Parent != null ? Parent.BackColor : Tema.Superficie);

        string[] dirs = { "dir", "baixo", "esq", "cima" };
        float[] inicio = { -45f + 1.5f, 45f + 1.5f, 135f + 1.5f, 225f + 1.5f };
        for (int i = 0; i < 4; i++)
        {
            Color fundo = Tema.Elevado;
            Color seta = Enabled ? Tema.Texto : Tema.TextoOff;
            if (Enabled && _premido == dirs[i]) { fundo = Tema.Primario; seta = Color.White; }
            else if (Enabled && _sobre == dirs[i]) fundo = Tema.Hover;
            using (GraphicsPath p = Fatia(inicio[i], 87f))
            {
                using (SolidBrush b = new SolidBrush(fundo)) g.FillPath(b, p);
                using (Pen pen = new Pen(Tema.Borda, 1f)) g.DrawPath(pen, p);
            }
            Chevron(g, dirs[i], seta);
        }

        // centro = parar
        float rc = RaioCentro;
        Color fc = Tema.Elevado;
        if (Enabled && _premido == "centro") fc = Tema.Perigo; else if (Enabled && _sobre == "centro") fc = Tema.Hover;
        RectangleF cr = new RectangleF(Centro.X - rc, Centro.Y - rc, rc * 2, rc * 2);
        using (SolidBrush b = new SolidBrush(fc)) g.FillEllipse(b, cr);
        using (Pen pen = new Pen(Tema.Borda, 1f)) g.DrawEllipse(pen, cr);
        float ic = rc * 1.1f;
        Icones.Desenhar(g, "parar", new RectangleF(Centro.X - ic / 2f, Centro.Y - ic / 2f, ic, ic),
            (Enabled && _premido == "centro") ? Color.White : (Enabled ? Tema.TextoSuave : Tema.TextoOff));

        if (Focused && ShowFocusCues)
        {
            using (Pen pen = new Pen(Tema.AcentoClaro, 2f))
                g.DrawEllipse(pen, 1f, 1f, Width - 2f, Height - 2f);
        }
    }
}

// ---------------------------------------------------------------------------
// Menus (ContextMenuStrip / bandeja) no tema escuro
// ---------------------------------------------------------------------------
public class TemaCores : ProfessionalColorTable
{
    public TemaCores() { UseSystemColors = false; }
    public override Color ToolStripDropDownBackground { get { return Tema.Elevado; } }
    public override Color ImageMarginGradientBegin { get { return Tema.Elevado; } }
    public override Color ImageMarginGradientMiddle { get { return Tema.Elevado; } }
    public override Color ImageMarginGradientEnd { get { return Tema.Elevado; } }
    public override Color MenuBorder { get { return Tema.BordaForte; } }
    public override Color MenuItemBorder { get { return Tema.Hover; } }
    public override Color MenuItemSelected { get { return Tema.Hover; } }
    public override Color MenuItemSelectedGradientBegin { get { return Tema.Hover; } }
    public override Color MenuItemSelectedGradientEnd { get { return Tema.Hover; } }
    public override Color SeparatorDark { get { return Tema.Borda; } }
    public override Color SeparatorLight { get { return Tema.Borda; } }
}

public class TemaRenderer : ToolStripProfessionalRenderer
{
    public TemaRenderer() : base(new TemaCores()) { RoundedEdges = false; }

    protected override void OnRenderItemText(ToolStripItemTextRenderEventArgs e)
    {
        e.TextColor = e.Item.Enabled ? Tema.Texto : Tema.TextoOff;
        base.OnRenderItemText(e);
    }
}
