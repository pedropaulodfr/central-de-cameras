using System;
using System.IO;
using System.Text;

// CLI do relay RTSP, para o gateway (servidor Node.js).
// Uso:  RtspRelayCli.exe <host> [porta] [portaLocal]
// Imprime no stdout a porta local efetivamente aberta e fica rodando.
//
// Este arquivo COMPATILHIZA o RtspRelay.cs original (nao o altera):
// o codigo do relay continua identico ao do app desktop.
public static class RtspRelayCli
{
    public static int Main(string[] args)
    {
        if (args.Length < 1)
        {
            Console.Error.WriteLine("uso: RtspRelayCli.exe <host> [porta] [portaLocal]");
            return 2;
        }

        string host = args[0];
        int porta = args.Length > 1 ? int.Parse(args[1]) : 554;
        int portaLocal = args.Length > 2 ? int.Parse(args[2]) : 0;

        RtspRelay relay = new RtspRelay(host, porta, portaLocal);
        relay.Start();

        // A saida tem que ser a PRIMEIRA coisa no stdout: o Node le ate
        // a quebra de linha e usa a porta para montar a URL.
        Console.Out.WriteLine(relay.Port);
        Console.Out.Flush();

        // Fica vivo ate o processo ser encerrado.
        Console.CancelKeyPress += delegate { relay.Stop(); };
        System.Threading.Thread.Sleep(System.Threading.Timeout.Infinite);
        return 0;
    }
}
