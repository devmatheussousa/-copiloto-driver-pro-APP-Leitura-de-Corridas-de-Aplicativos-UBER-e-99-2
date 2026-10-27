# Copiloto Driver Pro

Assistente Android nativo (Kotlin + Jetpack Compose) para análise de ofertas de corrida:
semáforo financeiro (R$/km e R$/hora), painel flutuante sobre outros apps, regiões de
atoruação no mapa, alerta de corrida com parada, leitura automática via AccessibilityService
e modo manual de reserva.

O app **não aceita nem recusa corridas automaticamente**, não envia dados a servidores e
não requer login.

---

## 1. Como abrir e compilar

1. Instale o **Android Studio** (Koala ou mais novo) com **JDK 17** embutido.
2. Android Studio → **File → Open** → selecione a pasta `CopilotoDriverPro`.
3. Se o Android Studio perguntar sobre o **Gradle wrapper**, aceite criar/usar o padrão
   (o `gradle-wrapper.properties` já está com Gradle 8.7).
4. **Sincronize** o projeto (ele baixa AGP 8.5.2, Kotlin 2.0.20 e as libs).
5. Não precisa de chave de API: o mapa de regiões é desenhado pelo próprio app (sem Google Maps).
6. **Run ▶** em um aparelho com Android 8.0+ (API 26+).
7. APK de teste: **Build → Build Bundle(s)/APK(s) → Build APK(s)** — o arquivo fica em
   `app/build/outputs/apk/debug/app-debug.apk`.

Observação: este ambiente não tem SDK Android, portanto **nenhum APK foi compilado aqui**.
Os passos acima geram o APK na sua máquina.

## 2. Primeiro uso (ordem recomendada)

1. Abra o app → **Permissões** → **Conceder sobreposição** (necessária para o botão flutuante).
2. **Abrir ajustes de acessibilidade** → ative *Copiloto Driver Pro — leitura de ofertas*.
3. **Minhas regiões** → busque sua cidade (ou toque em *Minha localização*); o mapa mostra os
   bairros/setores reais. Toque num bairro para marcar/desmarcar e use **Salvar regiões**.
   Os avisos de dentro/fora e o aviso na corrida passam a usar esses bairros.
4. **Configurações** → ajuste os limites do semáforo, posição e tamanho do painel.
5. Ative **Botão flutuante** e **Copiloto inteligente**.
6. Abra a Uber: o botão circular aparece sobre ela. Arraste para onde quiser; toque para
   expandir o painel com R$/km, R$/hora, semáforo, situação da região e alerta de parada.

## 3. Estrutura do projeto

```
app/src/main/java/com/copilotodriverpro/
├── MainActivity.kt              # Activity única + NavHost
├── CopilotApp.kt                # dependências de processo (singletons)
├── models/                      # RideOffer, Region, LatLang, enums
├── settings/                    # DriverSettings, SettingsRepository (DataStore), ViewModel
├── analyzer/                    # RideAnalyzer: cálculo e semáforo (puro, testável)
├── recognition/                 # RideTextParser (regex), ImageOcrProcessor (ML Kit), geocoder
├── accessibility/               # CopilotAccessibilityService
├── overlay/                     # FloatingPanelService (WindowManager + Compose)
├── regions/                     # GeoUtils (haversine, ray casting), RegionRepository
├── maps/                        # RegionsScreen + RegionMap (mapa em Canvas do Compose) + MapMath
├── ui/theme, ui/components, ui/screens
└── utils/                       # Permissions
app/src/test/java/com/copilotodriverpro/   # testes unitários JVM
```

## 4. Regras de cálculo

- **R$/km** = valor ÷ (km até o passageiro + km da viagem) — o mesmo cálculo do "R$/km" que o
  Uber e a 99 mostram no card. **R$/hora** e **R$/min** = valor ÷ (tempo até o passageiro + tempo
  da viagem).
- **R$/hora** = valor ÷ (tempo estimado em horas).
- Sem distância ou sem tempo → exibe **"—"** e não calcula (nada de valor inventado).
- Semáforo: **vermelho** abaixo do mínimo, **amarelo** entre mínimo e máximo, **verde**
  acima do máximo. Verde **não é garantia de lucro**: o app não calcula custos de
  combustível, depreciação ou impostos, então não apresenta lucro líquido.

## 5. Parada: níveis de confiança

- **CONFIRMED** — frases explícitas: "corrida com parada", "parada adicional",
  "múltiplas paradas", "N paradas" → alerta vermelho.
- **POSSIBLE** — palavra "parada" em contexto ambíguo → mostra
  *"Possível parada — confirme na oferta"* em vez de afirmar.
- A parada nunca decide sozinha aceitar/recusar: é um alerta à sua decisão.

## 6. Regiões de atuação (Minhas regiões)

Você desenha as áreas onde quer atuar e o app avisa, **pelo GPS**, se você está dentro ou fora.

- Círculo (centro + raio) ou polígono (≥ 3 vértices), com nome, ativar/desativar, **editar** e excluir.
- **Só as regiões ativas contam.** Desativar tira a região do aviso sem apagá-la.
- **Aviso por posição (novo):** "DENTRO DA REGIÃO DE ATUAÇÃO" (verde) / "FORA DA REGIÃO DE ATUAÇÃO" (vermelho):
  - selo fixo no topo da tela, por cima do Uber/99 (cresce 6 s quando você entra/sai);
  - anel verde/vermelho no botão flutuante e faixa no painel expandido;
  - notificação, vibração (fora = 3 longas; dentro = 1 curta) e voz — cada um liga/desliga.
- **Anti-oscilação:** a troca só vale após 2 leituras seguidas e há uma margem na borda (30 m +
  metade da imprecisão do GPS), então o aviso não fica piscando perto do limite.
- Sem sinal de GPS = "GPS SEM SINAL" (cinza). **Nunca** vira "fora" por falta de dado.
- Criar região pela sua posição atual ("Usar minha posição como centro" / "+ Minha posição").
- A posição fica só no aparelho (LocationManager do Android; não usa Google Play Services).
- Sem coordenada reconhecida na oferta o resultado é "não identificado" — nunca "fora".
- Tablet/tela larga (≥ 840 dp): mapa grande à esquerda e painel à direita.

### 6.1 Aviso "esta corrida me joga para fora da região?" (novo)

Quando uma corrida aparece, o card sobre o Uber/99 compara o **embarque** e o **destino** da oferta
com as regiões **ativas** e mostra uma faixa:

| Faixa no card | Significa |
|---|---|
| 🟥 **ESTA CORRIDA TE LEVA PARA FORA DA REGIÃO** (+ "Destino a 4,2 km da borda") | você está dentro e o destino fica fora |
| 🟥 **DESTINO FORA DA REGIÃO** | destino fora (GPS sem sinal ou você já estava fora) |
| 🟥 **EMBARQUE FORA DA REGIÃO** / **EMBARQUE E DESTINO FORA** | o passageiro está fora da região |
| 🟩 **EMBARQUE E DESTINO DENTRO DA REGIÃO** | tudo dentro (mostra o nome da região) |
| 🟩 **ESTA CORRIDA TE TRAZ DE VOLTA PARA A REGIÃO** | você está fora e o destino é dentro |
| ⬜ **Destino não identificado** | o Geocoder não achou o endereço — confira na oferta |
| 🟧 **Verificando região…** | convertendo o endereço (some em instantes) |

Com o card minimizado aparece "⚠ FORA DA REGIÃO". Se a corrida sai da região o app ainda **vibra
2 vezes** e (opcional) **fala** o aviso — uma vez por corrida.

**Ativar:** *Configurações → Regiões de atuação* (ou *Minhas regiões → Quando uma corrida aparecer*):
- *Avisar se a corrida sai da região* — liga a faixa no card.
- *Vibrar* / *Falar* — avisos extras.
- *Verificar embarque* / *Verificar destino* — o que é conferido.
- *Recusar se embarque/destino fora* — além de avisar, o copiloto marca a corrida como **NÃO PEGAR**.
  Desligado, só avisa e a decisão continua só pelo R$/km e R$/hora.

Detalhes técnicos: embarque e destino são geocodificados **em paralelo**, com o Geocoder procurando
primeiro dentro de uma caixa em volta das suas regiões (evita achar a "Rua X" de outra cidade). Se
demorar mais de 2 s o card aparece com "Verificando região…" e o resultado entra depois.
**Limite honesto:** o endereço vem do texto da oferta e o Geocoder é de melhor esforço; endereço
incompleto → "não identificado". Bairro parecido em outra cidade pode, raramente, ser confundido.

**Como o GPS fica ligado com o Uber aberto:** o aviso usa o serviço em primeiro plano (a notificação
"Ninja Go ativo"). Abra o app uma vez, permita a localização e crie uma região ativa; o serviço sobe
sozinho. "Fechar" na notificação desliga o botão flutuante **e** o aviso de região.

## 7. Leitura automática — como funciona e seus limites

**AccessibilityService** (uso principal):
- Só lê o texto visível da tela; não clica, não toca, não digita.
- Com *"Ler apenas Uber/99"* ligado, só processa pacotes identificados:
  `com.ubercab`, `com.ubercab.driver`, `com.taxify.android`, `com.taxis99`, `br.com.taxis99`.
- Leitura amortecida (mín. 1,5 s entre análises) para não sobrecarregar.
- O conteúdo nunca sai do aparelho.

**Limitações conhecidas (honestas):**
- O Uber não expõe API pública de ofertas; o app depende do texto que o Android entrega
  ao serviço de acessibilidade. **Cada versão do Uber pode mudar layout/ids** — os campos
  reconhecidos variam. O parser tolera variações, mas não há garantia de compatibilidade
  com todas as versões.
- Alguns apps dificultam leitura de conteúdo (ofuscação, canvas, imagens). Nesses casos o
  serviço não enxerga texto e o **modo manual** entra como reserva.
- **OCR de imagem** (ML Kit, local) funciona com capturas que você escolhe manualmente
  (Photo Picker). Não há captura oculta ou contínua de tela.
- Endereços viram coordenadas pelo **Geocoder do sistema** (melhor esforço, offline quando
  o aparelho tem cache); endereço incompleto pode não geocodificar.
- **Segundo plano**: serviços em primeiro plano podem ser mortos por economia de bateria
  (otimizações do fabricante). Se o botão sumir, desative a otimização de bateria para o
  app nas configurações do sistema.
- Google Play restringe AccessibilityService em apps publicados na loja. Este projeto é
  para **uso pessoal/instalação direta (APK)**, o que não altera as leis locais de
  interceptação de tela — a autorização é sua e a leitura é local.
- Não tente usar isto para contornar proteções da Uber/99 ou do Android; o app foi feito
  apenas para ler o que o sistema já entrega a serviços de acessibilidade autorizados.

## 8. Testes

**Unitários (JVM, rodam sem dispositivo):**

```
./gradlew testDebugUnitTest
```

- `RideAnalyzerTest` — R$/km, R$/hora, limites, cores, dados ausentes, soma da distância
  até o passageiro.
- `RideTextParserTest` — valor, tempo (min e h), nota, parada confirmada/ambígua,
  números brasileiros ("1.234,56"), texto sem oferta.
- `GeoUtilsTest` — círculos, polígonos, dentro/fora, regiões inativas, embarque vs destino,
  "desconhecido" nunca vira "fora".
- `RideRegionAdvisorTest` — faixa do card (te leva para fora, destino/embarque fora, dentro, traz de
  volta, não identificado, verificando), distância até a borda e caixa de busca do Geocoder.
- `ZoneTrackerTest` — aviso por GPS: primeira leitura, confirmação de 2 leituras, histerese na
  borda, sem sinal = desconhecido, distância até a borda do polígono.

**Testes reais no celular (não automatizáveis aqui):**
1. Conceder sobreposição e ver o botão sobre a Uber.
2. Arrastar o botão, reabrir o app e conferir que a posição foi lembrada.
3. Expandir/recolher o painel sem sair da Uber.
4. Oferta real da Uber com valores conhecidos → conferir R$/km e R$/hora calculados.
5. Oferta com parada → alerta vermelho aparece e some com a nova oferta.
6. Região circular em volta da sua casa → embarque "DENTRO"; endereço distante → "FORA".
6a. Oferta com destino fora da região → faixa vermelha "TE LEVA PARA FORA" + 2 vibrações; destino dentro → faixa verde.
6b. Com o GPS ligado, andar para fora da região → selo vermelho "FORA DA REGIÃO DE ATUAÇÃO" + vibração; voltar → verde.
7. Restaurar padrões pede confirmação e volta tudo ao padrão.

## 9. Permissões e por quê

| Permissão | Motivo |
|---|---|
| `SYSTEM_ALERT_WINDOW` | botão flutuante e painel sobre outros apps (concedida por você em Ajustes) |
| `BIND_ACCESSIBILITY_SERVICE` | ler o texto visível das ofertas (ativada por você em Ajustes › Acessibilidade) |
| `POST_NOTIFICATIONS` | notificação obrigatória do serviço em primeiro plano (Android 13+) |
| `FOREGROUND_SERVICE` / `SPECIAL_USE` | mantém o painel vivo enquanto você dirige |
| `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` | saber se você está dentro ou fora da região de atuação (só no aparelho) |
| `FOREGROUND_SERVICE_LOCATION` | manter o GPS do aviso de região com o Uber aberto (Android 14+) |
| `VIBRATE` | vibração do aviso dentro/fora |
| `INTERNET` | download do modelo ML Kit e carregamento do mapa — **nenhum dado de corrida sai do aparelho** |

Sem login, sem cadastro, sem analytics, sem servidores próprios.

## 7. Solução de problemas — o card/popup não aparece

1. Android 13+ (APK instalado fora da Play Store): em **Configurações → Apps → Copiloto Driver Pro → ⋮ → Permitir configurações restritas**, e só então ative o serviço de acessibilidade.
2. Ative *Copiloto Driver Pro — leitura de ofertas* em Acessibilidade (o card usa a janela de acessibilidade, não precisa mais de "Exibir sobre outros apps"; o botão flutuante ainda precisa).
3. Deixe o app fora da otimização de bateria (Bateria → Sem restrições), senão o Android mata o serviço.
4. Logcat: filtre pela tag `Copiloto`. "Card de corrida criado" = janela OK. "Tela de oferta ... parse=false" = o texto da Uber mudou e o parser precisa de ajuste (veja o texto capturado no painel de diagnóstico).

## 10. Compatibilidade e desempenho

**Versões:** Android 8.0 (API 26) até Android 15 (API 35). Celulares e tablets ARM (arm64-v8a e
armeabi-v7a no release). O app aceita tela pequena, grande e extragrande, rotação e multi-janela.

**Tablets:** o painel flutuante se adapta à largura da tela; o card da corrida fica 20% maior em
tablets (menor lado ≥ 600 dp); a tela Minhas regiões usa duas colunas em tela larga. Tablet **só
Wi-Fi não tem GPS**: o aviso de região funciona por redes, com precisão pior (o app avisa isso).
O app do motorista Uber/99 pode não instalar ou rodar em alguns tablets — isso depende do Uber/99,
não deste app.

**Histórico da 99:** uma corrida vira "aceita" por (a) toque em Aceitar, (b) tela de corrida em andamento
ou (c) o endereço de embarque/destino da oferta aparecer na tela do app depois que o card sumiu
(`TripAddressMatch`). O (c) não depende do texto dos botões da 99, que muda entre versões.

**Sem Google Play Services** (ex.: alguns Huawei): tudo funciona, inclusive o mapa da tela Minhas
regiões (ele não usa Google Maps). O GPS do aviso usa o LocationManager do sistema.

**Marcas com economia de bateria agressiva** (Xiaomi/Redmi/Poco, Samsung, Motorola, Oppo, Realme,
Vivo): permita "Início automático"/"Exibir pop-ups" e escolha "Sem restrições" na bateria para o
Ninja Go (a tela inicial tem o botão **Ajustar bateria**). Android 13+: ative "Permitir
configurações restritas" antes de ligar a acessibilidade (item 7).

**Otimizações desta versão:** R8 + redução de recursos no release; só idiomas pt/en; sem ABIs x86 no
release; fluxos de configurações/regiões sem recomposição à toa; leitura do botão flutuante sem
travar a tela principal; GPS só ligado quando alguém usa (tela aberta ou serviço ativo).

**Gerar o APK de release:** *Build → Generate Signed Bundle / APK* (o R8 está ativo; se algo sumir no
release, veja o Logcat e acrescente `-keep` em `app/proguard-rules.pro`).


## Região de atuação por bairros (mapa interativo)

- **Mapa:** desenhado direto num `Canvas` do Compose (`maps/RegionMap.kt`), sem Google Maps, sem chave
  de API e sem tiles de fundo. Cada bairro vira um `Path` pronto (montado em segundo plano); arrastar e
  dar zoom só muda a matriz de transformação, marcar um bairro só troca a cor. Só desenha o que está na
  tela. Projeção Web Mercator e zoom na mesma escala do Google (`maps/MapMath.kt`).
- **Ruas (botão "Ruas"):** linhas das vias do OpenStreetMap, por cima dos bairros. Baixa em partes (vias
  grandes + ruas em quadrantes de ~13 km; cada parte vira um arquivo em `files/ruas/`, então se uma falhar
  só ela é repetida), lê o JSON em fluxo, simplifica as linhas (4 m) e grava em binário. No mapa, cada tipo
  de via aparece só a partir de um zoom (rodovias de longe, ruas só de bem perto) e é desenhado em
  quadradinhos de ~1,2 km — só os que estão na tela. Arquivos: `regions/StreetGeo.kt`,
  `regions/StreetRepository.kt`, `maps/RegionMap.kt`, `StreetGeoTest.kt`.
- **Dados geográficos:** OpenStreetMap — Nominatim (acha a cidade e o contorno do município) e
  Overpass API (relações administrativas nível 10 = bairro; se não houver, nível 9 = distrito/setor).
  Baixa uma vez por cidade e guarda em `files/bairros/` no aparelho; depois funciona sem internet.
- **Limitação:** a cobertura depende do mapeamento do OSM. Sem bairros mapeados, o app avisa
  "O OpenStreetMap não tem limites de bairros mapeados para <cidade>" — nunca desenha divisões
  aproximadas. Contornos são simplificados (~8 m) e buracos internos (enclaves) são ignorados.
- **Persistência:** cada bairro salvo vira `Region` POLYGON com id `bairro:<id OSM>` (contornos extras:
  `bairro:<id>#1`…) no mesmo `RegionRepository` de antes — salvar de novo nunca duplica, e o
  `ZoneTracker`/`RideRegionAdvisor` continuam funcionando sem mudanças. Regiões circulares/polígonos
  antigos continuam valendo (botão de ajustes ⚙ → "Regiões antigas").
- **Arquivos:** `maps/RegionsScreen.kt`, `maps/RegionMap.kt`, `maps/MapMath.kt`, `regions/Neighborhood.kt`, `regions/NeighborhoodRepository.kt`,
  `CopilotApp.kt`, `app/build.gradle.kts` (só dependência de teste) e `NeighborhoodGeoTest.kt`.

## v2.6 — Corridas aceitas automáticas

- Cada corrida **aceita** no Uber ou na 99 entra sozinha no **Histórico** e vira um ganho
  ("Corrida Uber" / "Corrida 99") em **Lançamentos** (marcado como "Automático").
- Despesas, gorjetas e demais lançamentos continuam **manuais**.
- O app só observa a tela: o aceite é detectado pelo toque em Aceitar e/ou pela tela de corrida
  em andamento que aparece logo depois (`RideAcceptTracker`). Recusar/expirar não grava nada.
- Histórico com o mesmo visual de Lançamentos (Hoje / Esta Semana / Este Mês), filtro Uber/99,
  média R$/km e R$/h, editar valor e excluir (atualiza/remove o lançamento ligado).
- Banco migrado v1 → v2 sem apagar lançamentos e turnos existentes.

## v2.7 — Aba Ganhos (no lugar de Turnos) + botão "+" amarelo

- Barra inferior: Início · **Ganhos** · "+" · Lançamentos · Histórico. A tela Turnos continua
  existindo (rota `shifts`, acessível pelo card TURNO da tela Início), só saiu da barra.
- Ganhos: saldo do dia (lançamentos de ganho), "Ver extrato" abre Lançamentos, cards do dia
  (corridas aceitas, tempo online pelos turnos, ganho por corrida, nota média dos passageiros),
  gráfico de 7/14/30 dias com hoje em amarelo e meta diária salva nas configurações (`dailyGoal`).
- Sem dado = estado vazio ("—" e mensagem); nada é inventado.
- Botão "+" central agora amarelo com ícone preto; comportamento igual.
