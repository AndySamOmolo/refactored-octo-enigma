import React, { useMemo, useState } from 'react';
import {
  Pressable,
  ScrollView,
  StyleSheet,
  Switch,
  Text,
  TextInput,
  View,
} from 'react-native';
import { useAppState } from '../../src/context/AppStateContext';
import { COLORS, RADIUS, SPACING } from '../../src/theme';

const RISK_STYLES = [
  { label: 'Safe', mult: 0.15, maxPct: 0.03 },
  { label: 'Balanced', mult: 0.25, maxPct: 0.05 },
  { label: 'Bold', mult: 0.4, maxPct: 0.07 },
];

export default function MyPicksScreen() {
  const { settings, paperBets, updateSettings, settlePaperBet, deletePaperBet } = useAppState();
  const [bankrollText, setBankrollText] = useState(String(settings.bankroll));

  const stats = useMemo(() => {
    let settled = 0;
    let won = 0;
    let netPnl = 0;

    for (const b of paperBets) {
      if (b.status === 'WON') {
        settled++;
        won++;
        netPnl += b.stakeAmount * (b.decimalOdds - 1);
      } else if (b.status === 'LOST') {
        settled++;
        netPnl -= b.stakeAmount;
      }
    }
    const winRate = settled > 0 ? Math.round((won / settled) * 100) : 0;
    return { settled, won, netPnl, winRate };
  }, [paperBets]);

  return (
    <ScrollView style={styles.container} contentContainerStyle={styles.content}>
      {/* Practice Balance KPI Row */}
      <View style={styles.kpiRow}>
        <View style={styles.kpiBox}>
          <Text style={styles.kpiLabel}>Practice Balance</Text>
          <Text style={styles.kpiVal}>${(settings.bankroll + stats.netPnl).toFixed(0)}</Text>
        </View>
        <View style={styles.kpiBox}>
          <Text style={styles.kpiLabel}>Total Profit</Text>
          <Text
            style={[
              styles.kpiVal,
              { color: stats.netPnl >= 0 ? COLORS.emerald : COLORS.crimson },
            ]}
          >
            {stats.netPnl >= 0 ? '+' : ''}${stats.netPnl.toFixed(0)}
          </Text>
        </View>
        <View style={styles.kpiBox}>
          <Text style={styles.kpiLabel}>Win Rate</Text>
          <Text style={styles.kpiVal}>{stats.winRate}%</Text>
        </View>
      </View>

      {/* Simple Settings */}
      <View style={styles.panel}>
        <Text style={styles.sectionTitle}>Betting Budget &amp; Weekly Updates</Text>

        <View style={styles.settingRow}>
          <Text style={styles.settingLabel}>Starting Practice Balance ($)</Text>
          <TextInput
            style={styles.numInput}
            keyboardType="numeric"
            value={bankrollText}
            onChangeText={setBankrollText}
            onEndEditing={() => {
              const v = Number.parseFloat(bankrollText);
              if (Number.isFinite(v) && v >= 50) updateSettings({ bankroll: v });
            }}
          />
        </View>

        <View style={{ gap: 8 }}>
          <Text style={styles.settingLabel}>Suggested Bet Size Style</Text>
          <View style={styles.riskRow}>
            {RISK_STYLES.map((r) => {
              const active = Math.abs(settings.kellyMultiplier - r.mult) < 0.05;
              return (
                <Pressable
                  key={r.label}
                  style={[styles.riskBtn, active && styles.riskBtnActive]}
                  onPress={() =>
                    updateSettings({ kellyMultiplier: r.mult, maxStakePct: r.maxPct })
                  }
                >
                  <Text style={[styles.riskText, active && { color: COLORS.accent }]}>
                    {r.label}
                  </Text>
                </Pressable>
              );
            })}
          </View>
        </View>

        <View style={styles.settingRow}>
          <View style={{ flex: 1 }}>
            <Text style={styles.settingLabel}>Auto-Update Once a Week</Text>
            <Text style={styles.settingSub}>
              Automatically downloads fresh free sports data on your phone every 7 days when online.
            </Text>
          </View>
          <Switch
            value={settings.autoSyncWeekly}
            onValueChange={(v) => updateSettings({ autoSyncWeekly: v })}
            trackColor={{ false: COLORS.border, true: COLORS.accent }}
          />
        </View>

        <Text style={styles.disclaimer}>
          Practice &amp; analysis tracker only—this app never places real-money bets or guarantees wins.
        </Text>
      </View>

      {/* Saved Picks */}
      <View style={styles.panel}>
        <Text style={styles.sectionTitle}>Saved Picks ({paperBets.length})</Text>
        {paperBets.length === 0 ? (
          <Text style={styles.emptyText}>
            No picks saved yet. Open any game in the Games tab and tap Save to track your results here.
          </Text>
        ) : (
          paperBets.map((bet) => (
            <View key={bet.id} style={styles.betCard}>
              <View style={styles.rowBetween}>
                <Text style={styles.betMatch}>{bet.matchup}</Text>
                <Text
                  style={[
                    styles.statusTag,
                    bet.status === 'WON'
                      ? { color: COLORS.emerald }
                      : bet.status === 'LOST'
                      ? { color: COLORS.crimson }
                      : { color: COLORS.amber },
                  ]}
                >
                  {bet.status}
                </Text>
              </View>
              <Text style={styles.betMeta}>
                {bet.selection} @ {bet.decimalOdds.toFixed(2)} · Bet ${bet.stakeAmount.toFixed(0)} · Win Chance {Math.round(bet.modelProb * 100)}%
              </Text>
              <View style={styles.actionRow}>
                <Pressable style={styles.smallBtn} onPress={() => settlePaperBet(bet.id, 'WON')}>
                  <Text style={[styles.smallBtnText, { color: COLORS.emerald }]}>Won</Text>
                </Pressable>
                <Pressable style={styles.smallBtn} onPress={() => settlePaperBet(bet.id, 'LOST')}>
                  <Text style={[styles.smallBtnText, { color: COLORS.crimson }]}>Lost</Text>
                </Pressable>
                <Pressable style={styles.smallBtn} onPress={() => settlePaperBet(bet.id, 'VOID')}>
                  <Text style={styles.smallBtnText}>Refund</Text>
                </Pressable>
                <Pressable style={styles.smallBtn} onPress={() => deletePaperBet(bet.id)}>
                  <Text style={styles.smallBtnText}>Remove</Text>
                </Pressable>
              </View>
            </View>
          ))
        )}
      </View>
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: COLORS.bg },
  content: { padding: SPACING.md, gap: 14, paddingBottom: 36 },
  kpiRow: { flexDirection: 'row', gap: 8 },
  kpiBox: {
    flex: 1,
    backgroundColor: COLORS.surface,
    borderWidth: 1,
    borderColor: COLORS.border,
    borderRadius: RADIUS.lg,
    padding: 13,
    alignItems: 'center',
  },
  kpiLabel: { fontSize: 11, fontWeight: '600', color: COLORS.textMuted },
  kpiVal: { fontSize: 19, fontWeight: '800', color: COLORS.text, marginTop: 4 },
  panel: {
    backgroundColor: COLORS.surface,
    borderRadius: RADIUS.lg,
    borderWidth: 1,
    borderColor: COLORS.border,
    padding: 16,
    gap: 12,
  },
  sectionTitle: { fontSize: 15, fontWeight: '800', color: COLORS.text },
  settingRow: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: 12 },
  settingLabel: { fontSize: 14, fontWeight: '700', color: COLORS.text },
  settingSub: { fontSize: 12, color: COLORS.textMuted, marginTop: 2, lineHeight: 17 },
  numInput: {
    backgroundColor: COLORS.surfaceRaised,
    borderWidth: 1,
    borderColor: COLORS.border,
    borderRadius: RADIUS.sm,
    color: COLORS.text,
    fontSize: 15,
    fontWeight: '700',
    paddingHorizontal: 10,
    paddingVertical: 6,
    minWidth: 86,
    textAlign: 'right',
  },
  riskRow: { flexDirection: 'row', gap: 8 },
  riskBtn: {
    flex: 1,
    paddingVertical: 9,
    borderRadius: RADIUS.sm,
    borderWidth: 1,
    borderColor: COLORS.border,
    backgroundColor: COLORS.surfaceRaised,
    alignItems: 'center',
  },
  riskBtnActive: { borderColor: COLORS.accent, backgroundColor: COLORS.accentDim },
  riskText: { fontSize: 13, fontWeight: '700', color: COLORS.textSecondary },
  disclaimer: {
    fontSize: 12,
    color: COLORS.textMuted,
    lineHeight: 18,
    borderTopWidth: 1,
    borderTopColor: COLORS.border,
    paddingTop: 10,
  },
  emptyText: { fontSize: 13, color: COLORS.textMuted, lineHeight: 19 },
  betCard: {
    backgroundColor: COLORS.surfaceRaised,
    borderRadius: RADIUS.md,
    padding: 12,
    gap: 6,
    borderWidth: 1,
    borderColor: COLORS.border,
  },
  rowBetween: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
  betMatch: { fontSize: 14, fontWeight: '800', color: COLORS.text, flex: 1 },
  statusTag: { fontSize: 11, fontWeight: '800' },
  betMeta: { fontSize: 12, color: COLORS.textSecondary },
  actionRow: { flexDirection: 'row', gap: 8, marginTop: 4 },
  smallBtn: {
    paddingHorizontal: 11,
    paddingVertical: 6,
    borderRadius: RADIUS.sm,
    borderWidth: 1,
    borderColor: COLORS.border,
    backgroundColor: COLORS.surface,
  },
  smallBtnText: { fontSize: 11, fontWeight: '700', color: COLORS.textSecondary },
});
