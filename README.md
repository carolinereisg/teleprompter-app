# Teleprompter (Android)

App de gravação de vídeo com teleprompter transparente em tela cheia.

## O que faz
- Cole textos grandes (botão **Colar texto**, ou "Compartilhar → Teleprompter" a partir de outro app).
- Ajuste **tamanho da letra**, **velocidade** e **escurecimento do fundo** (0% = vídeo totalmente visível por trás do texto). Letra e velocidade também podem ser ajustadas durante a gravação (A−/A+, 🐢/🐇).
- **Tempo de gravação:** livre, 30 s, 1 min, 3 min ou personalizado (minutos + segundos). A gravação para sozinha no limite; o tempo pausado não conta.
- **Mão aberta ✋** na frente da câmera por ~meio segundo pausa a gravação e o texto; mostrar de novo retoma. O ícone ✋ no topo acende quando o app vê uma mão.
- Contagem regressiva de 3 s, câmera frontal/traseira, retrato ou paisagem.
- Vídeos salvos em **Galeria → Movies/Teleprompter** (Full HD quando o aparelho permite).

Requer Android 10 ou superior.

## Como gerar o APK
O APK é compilado automaticamente pelo GitHub Actions a cada envio para o repositório
(workflow `.github/workflows/build-apk.yml`). Depois de ~5 min:
- **Releases → latest → teleprompter.apk** (link direto, bom para baixar no celular), ou
- **Actions → última execução → Artifacts → teleprompter-apk**.

Também abre normalmente no Android Studio (nesse caso, baixe o modelo
`gesture_recognizer.task` do MediaPipe para `app/src/main/assets/`).

## Instalar no celular
Baixe o `teleprompter.apk` no celular, toque nele e permita "instalar apps desta fonte"
quando o Android pedir.
