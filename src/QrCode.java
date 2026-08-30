/*
 * Implementação compacta adaptada do QR Code generator library.
 * Copyright (c) Project Nayuki. Licença MIT.
 * https://www.nayuki.io/page/qr-code-generator-library
 */
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Gerador QR local, suficiente para os endereços curtos usados pelo MagaDrop. */
final class QrCode {
    private static final int[] DATA_CODEWORDS = {0, 19, 34, 55, 80, 108};
    private static final int[] ECC_CODEWORDS = {0, 7, 10, 15, 20, 26};

    private QrCode() {}

    static boolean[][] encodeText(String texto) {
        byte[] dados = texto.getBytes(StandardCharsets.UTF_8);
        int versao = 1;
        while (versao < DATA_CODEWORDS.length && !cabe(dados.length, versao)) versao++;
        if (versao >= DATA_CODEWORDS.length) throw new IllegalArgumentException("Texto muito longo para o QR code");

        byte[] blocoDados = montarDados(dados, versao);
        byte[] divisor = divisorReedSolomon(ECC_CODEWORDS[versao]);
        byte[] correcao = restoReedSolomon(blocoDados, divisor);
        byte[] codewords = Arrays.copyOf(blocoDados, blocoDados.length + correcao.length);
        System.arraycopy(correcao, 0, codewords, blocoDados.length, correcao.length);
        return montarMatriz(codewords, versao);
    }

    private static boolean cabe(int bytes, int versao) {
        return 4 + 8 + bytes * 8 <= DATA_CODEWORDS[versao] * 8;
    }

    private static byte[] montarDados(byte[] conteudo, int versao) {
        int capacidade = DATA_CODEWORDS[versao] * 8;
        StringBuilder bits = new StringBuilder(capacidade);
        adicionarBits(bits, 0b0100, 4);
        adicionarBits(bits, conteudo.length, 8);
        for (byte b : conteudo) adicionarBits(bits, b & 0xFF, 8);
        int terminador = Math.min(4, capacidade - bits.length());
        adicionarBits(bits, 0, terminador);
        while (bits.length() % 8 != 0) bits.append('0');

        byte[] resultado = new byte[DATA_CODEWORDS[versao]];
        int usados = bits.length() / 8;
        for (int i = 0; i < usados; i++) resultado[i] = (byte) Integer.parseInt(bits.substring(i * 8, i * 8 + 8), 2);
        for (int i = usados; i < resultado.length; i++) resultado[i] = (byte) (i % 2 == usados % 2 ? 0xEC : 0x11);
        return resultado;
    }

    private static void adicionarBits(StringBuilder destino, int valor, int quantidade) {
        for (int i = quantidade - 1; i >= 0; i--) destino.append(((valor >>> i) & 1) != 0 ? '1' : '0');
    }

    private static byte[] divisorReedSolomon(int grau) {
        byte[] resultado = new byte[grau];
        resultado[grau - 1] = 1;
        int raiz = 1;
        for (int i = 0; i < grau; i++) {
            for (int j = 0; j < resultado.length; j++) {
                resultado[j] = (byte) multiplicar(resultado[j] & 0xFF, raiz);
                if (j + 1 < resultado.length) resultado[j] ^= resultado[j + 1];
            }
            raiz = multiplicar(raiz, 2);
        }
        return resultado;
    }

    private static byte[] restoReedSolomon(byte[] dados, byte[] divisor) {
        byte[] resultado = new byte[divisor.length];
        for (byte dado : dados) {
            int fator = (dado ^ resultado[0]) & 0xFF;
            System.arraycopy(resultado, 1, resultado, 0, resultado.length - 1);
            resultado[resultado.length - 1] = 0;
            for (int i = 0; i < resultado.length; i++) resultado[i] ^= (byte) multiplicar(divisor[i] & 0xFF, fator);
        }
        return resultado;
    }

    private static int multiplicar(int x, int y) {
        int resultado = 0;
        for (int i = 7; i >= 0; i--) {
            resultado = (resultado << 1) ^ ((resultado >>> 7) * 0x11D);
            resultado ^= ((y >>> i) & 1) * x;
        }
        return resultado;
    }

    private static boolean[][] montarMatriz(byte[] dados, int versao) {
        int tamanho = versao * 4 + 17;
        boolean[][] modulos = new boolean[tamanho][tamanho];
        boolean[][] funcao = new boolean[tamanho][tamanho];

        for (int i = 0; i < tamanho; i++) {
            definirFuncao(modulos, funcao, 6, i, i % 2 == 0);
            definirFuncao(modulos, funcao, i, 6, i % 2 == 0);
        }
        desenharLocalizador(modulos, funcao, 3, 3);
        desenharLocalizador(modulos, funcao, tamanho - 4, 3);
        desenharLocalizador(modulos, funcao, 3, tamanho - 4);
        if (versao >= 2) desenharAlinhamento(modulos, funcao, tamanho - 7, tamanho - 7);
        desenharFormato(modulos, funcao, 0);
        inserirDados(modulos, funcao, dados);
        aplicarMascara(modulos, funcao, 0);
        desenharFormato(modulos, funcao, 0);
        return modulos;
    }

    private static void desenharLocalizador(boolean[][] modulos, boolean[][] funcao, int cx, int cy) {
        int tamanho = modulos.length;
        for (int dy = -4; dy <= 4; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                int x = cx + dx, y = cy + dy;
                if (x >= 0 && x < tamanho && y >= 0 && y < tamanho) {
                    int distancia = Math.max(Math.abs(dx), Math.abs(dy));
                    definirFuncao(modulos, funcao, x, y, distancia != 2 && distancia != 4);
                }
            }
        }
    }

    private static void desenharAlinhamento(boolean[][] modulos, boolean[][] funcao, int cx, int cy) {
        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                definirFuncao(modulos, funcao, cx + dx, cy + dy, Math.max(Math.abs(dx), Math.abs(dy)) != 1);
            }
        }
    }

    private static void desenharFormato(boolean[][] modulos, boolean[][] funcao, int mascara) {
        int dados = (1 << 3) | mascara; // Nível de correção L.
        int resto = dados;
        for (int i = 0; i < 10; i++) resto = (resto << 1) ^ ((resto >>> 9) * 0x537);
        int bits = ((dados << 10) | resto) ^ 0x5412;

        for (int i = 0; i <= 5; i++) definirFuncao(modulos, funcao, 8, i, bit(bits, i));
        definirFuncao(modulos, funcao, 8, 7, bit(bits, 6));
        definirFuncao(modulos, funcao, 8, 8, bit(bits, 7));
        definirFuncao(modulos, funcao, 7, 8, bit(bits, 8));
        for (int i = 9; i < 15; i++) definirFuncao(modulos, funcao, 14 - i, 8, bit(bits, i));

        int tamanho = modulos.length;
        for (int i = 0; i < 8; i++) definirFuncao(modulos, funcao, tamanho - 1 - i, 8, bit(bits, i));
        for (int i = 8; i < 15; i++) definirFuncao(modulos, funcao, 8, tamanho - 15 + i, bit(bits, i));
        definirFuncao(modulos, funcao, 8, tamanho - 8, true);
    }

    private static void inserirDados(boolean[][] modulos, boolean[][] funcao, byte[] dados) {
        int tamanho = modulos.length, indiceBit = 0;
        for (int direita = tamanho - 1; direita >= 1; direita -= 2) {
            if (direita == 6) direita = 5;
            for (int vertical = 0; vertical < tamanho; vertical++) {
                int y = ((direita + 1) & 2) == 0 ? tamanho - 1 - vertical : vertical;
                for (int j = 0; j < 2; j++) {
                    int x = direita - j;
                    if (!funcao[y][x] && indiceBit < dados.length * 8) {
                        modulos[y][x] = bit(dados[indiceBit >>> 3] & 0xFF, 7 - (indiceBit & 7));
                        indiceBit++;
                    }
                }
            }
        }
    }

    private static void aplicarMascara(boolean[][] modulos, boolean[][] funcao, int mascara) {
        for (int y = 0; y < modulos.length; y++) {
            for (int x = 0; x < modulos.length; x++) {
                boolean inverter = switch (mascara) {
                    case 0 -> (x + y) % 2 == 0;
                    case 1 -> y % 2 == 0;
                    case 2 -> x % 3 == 0;
                    default -> false;
                };
                if (!funcao[y][x] && inverter) modulos[y][x] = !modulos[y][x];
            }
        }
    }

    private static void definirFuncao(boolean[][] modulos, boolean[][] funcao, int x, int y, boolean escuro) {
        modulos[y][x] = escuro;
        funcao[y][x] = true;
    }

    private static boolean bit(int valor, int indice) {
        return ((valor >>> indice) & 1) != 0;
    }
}
