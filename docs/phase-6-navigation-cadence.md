# Fase 6 — cadência de navegação (abordagem substituída)

Este documento existe apenas para registrar uma abordagem que foi **descartada**.

A política anterior tentava controlar navegação por intervalos e horizontes temporais pré-definidos, incluindo valores como 500 ms, 2 s, 20 s e margens específicas por tipo de manobra. Essa interpretação foi removida porque transformava um problema físico contínuo em faixas arbitrárias e fazia constantes de calibração assumirem papel de regra de navegação.

Ela não deve ser usada como referência para implementação, teste ou calibração.

A política vigente está documentada em `phase-6-navigation-observation-policy.md` e parte dos seguintes princípios:

- todos os fixes entregues pelo provedor podem contribuir para a navegação;
- projeção e continuidade sobre a rota são interpretadas uma única vez por `RouteProgressTracker`;
- necessidade de observação deriva de confiança, geometria, incerteza, progresso e próxima decisão física relevante;
- a política produz um orçamento contínuo de observação, não uma tabela de cadências;
- `AndroidLocationProvider` apenas traduz a demanda do domínio para capacidades do Android;
- OSRM continua sendo a autoridade de roteamento e de geometria/interseções da rota;
- valores de aquisição do Android não definem quando uma manobra deve ser mostrada ou concluída.

Os baselines de localização usados fora de uma navegação ativa continuam pertencendo à sessão existente do aplicativo (movimento/repouso/desconexão) e não foram redefinidos por esta mudança.
