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
import { COLORS, FONTS, RADIUS, SPACING } from '../../src/theme';

export default function LedgerScreen() {
  const { settings, paperBets, updateSettings, settlePaperBet, deletePaperBet } = useAppState();
  const [bankrollText, setBankrollText] = useState(String(settings.bankroll));

  const stats = useMemo(() => {
    let settled = 0;
    let won = 0;
    let netPnl = 0;
    let totalStaked = 0;

    for (const b of paperBets) {
      if (b.status === 'WON') {
        settled++;
        won++;
        totalStaked += b.stakeAmount;
        netPnl += b.stakeAmount * (b.decimalOdds - 1);
      } else if (b.status === 'LOST') {
        settled++;
        totalStaked += b.stakeAmount;
        netPnl -= b.stakeAmount;
      }
    }
    const roi = totalStaked > 0 ? (netPnl / totalStaked) * 100 : 0;
    return { settled, won, netPnl, roi };
  }, [paperBets]);

  return (
    <ScrollView style={styles.container} contentContainerStyle={styles.content}>
      {/* Bankroll KPI Summary */}
      <View style={styles.kpiRow}>
        <View style={styles.kpiBox}>
          <Text style={styles.kpiLabel}>PAPER BANKROLL</Text>
          <Text style={styles.kpiVal}>${(settings.bankroll + stats.netPnl).toFixed(0)}</Text>
        </View>
        <View style={styles.kpiBox}>
          <Text style={styles.kpiLabel}>NET P&amp;L</Text>
          <Text
            style={[
              styles.kpiVal,
              { color: stats.netPnl >= 0 ? COLORS.emerald : COLORS.crimson },
            ]}
          >
            {stats.netPnl >= 0 ? '+' : ''}${stats.netPnl.toFixed(1)}
          </Text>
        </View>
        <View style={styles.kpiBox}>
          <Text style={styles.kpiLabel}>ROI</Text>
          <Text style={styles.kpiVal}>{stats.roi.toFixed(1)}%</Text>
        </View>
      </View>

      {/* Risk & Weekly Sync Controls */}
      <View style={styles.panel}>
        <Text style={styles.sectionTitle}>RISK GUARDRAILS &amp; WEEKLY SYNC</Text>
        <View style={styles.settingRow}>
          <Text style={styles.settingLabel}>Starting Bankroll ($)</Text>
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

        <View style={styles.settingRow}>
          <View style={{ flex: 1 }}>
            <Text style={styles.settingLabel}>Auto-Sync Weekly (Every 7 Days)</Text>
            <Text style={styles.settingSub}>
              Pulls free public feeds on-device when online and local pack is ≥ 7 days old.
            </Text>
          </View>
          <Switch
            value={settings.autoSyncWeekly}
            onValueChange={(v) => updateSettings({ autoSyncWeekly: v })}
            trackColor={{ false: COLORS.border, true: COLORS.accent }}
          />
        </View>

        <Text style={styles.disclaimer}>
          Analytical &amp; paper-trading tool only. Quarter-Kelly sizing is hard-capped at{' '}
          {(settings.maxStakePct * 100).toFixed(0)}% per pick and does not place real-money wagers.
        </Text>
      </View>

      {/* Logged Paper Picks */}
      <View style={styles.panel}>
        <Text style={styles.sectionTitle}>PAPER-BETTING LEDGER ({paperBets.length})</Text>
        {paperBets.length === 0 ? (
          <Text style={styles.emptyText}>
            No paper picks logged yet. Open any fixture in the Slate tab and tap LOG to track your model&apos;s performance.
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
                {bet.selection} @{bet.decimalOdds.toFixed(2)} · Stake ${bet.stakeAmount.toFixed(2)} · EV{' '}
                {bet.expectedValue >= 0 ? '+' : ''}
                {(bet.expectedValue * 100).toFixed(1)}%
              </Text>
              <View style={styles.actionRow}>
                <Pressable
                  style={styles.smallBtn}
                  onPress={() => settlePaperBet(bet.id, 'WON')}
                >
                  <Text style={[styles.smallBtnText, { color: COLORS.emerald }]}>WON</Text>
                </Pressable>
                <Pressable
                  style={styles.smallBtn}
                  onPress={() => settlePaperBet(bet.id, 'LOST')}
                >
                  <Text style={[styles.smallBtnText, { color: COLORS.crimson }]}>LOST</Text>
                </Pressable>
                <Pressable
                  style={styles.smallBtn}
                  onPress={() => settlePaperBet(bet.id, 'VOID')}
                >
                  <Text style={styles.smallBtnText}>VOID</Text>
                </Pressable>
                <Pressable
                  style={styles.smallBtn}
                  onPress={() => deletePaperBet(bet.id)}
                >
                  <Text style={styles.smallBtnText}>DEL</Text>
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
    borderRadius: RADIUS.md,
    padding: 12,
    alignItems: 'center',
  },
  kpiLabel: { fontFamily: FONTS.mono, fontSize: 10, color: COLORS.textMuted },
  kpiVal: { fontFamily: FONTS.mono, fontSize: 18, fontWeight: '700', color: COLORS.text, marginTop: 4 },
  panel: {
    backgroundColor: COLORS.surface,
    borderRadius: RADIUS.md,
    borderWidth: 1,
    borderColor: COLORS.border,
    padding: SPACING.md,
    gap: 12,
  },
  sectionTitle: { fontFamily: FONTS.mono, fontSize: 11, fontWeight: '700', color: COLORS.textSecondary },
  settingRow: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: 12 },
  settingLabel: { fontSize: 14, fontWeight: '600', color: COLORS.text },
  settingSub: { fontSize: 12, color: COLORS.textMuted, marginTop: 2 },
  numInput: {
    backgroundColor: COLORS.surfaceRaised,
    borderWidth: 1,
    borderColor: COLORS.border,
    borderRadius: RADIUS.sm,
    color: COLORS.text,
    fontFamily: FONTS.mono,
    fontSize: 14,
    paddingHorizontal: 10,
    paddingVertical: 6,
    minWidth: 84,
    textAlign: 'right',
  },
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
    borderRadius: RADIUS.sm,
    padding: 10,
    gap: 6,
    borderWidth: 1,
    borderColor: COLORS.border,
  },
  rowBetween: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
  betMatch: { fontSize: 13, fontWeight: '700', color: COLORS.text, flex: 1 },
  statusTag: { fontFamily: FONTS.mono, fontSize: 11, fontWeight: '700' },
  betMeta: { fontFamily: FONTS.mono, fontSize: 11, color: COLORS.textSecondary },
  actionRow: { flexDirection: 'row', gap: 8, marginTop: 4 },
  smallBtn: {
    paddingHorizontal: 10,
    paddingVertical: 5,
    borderRadius: RADIUS.sm,
    borderWidth: 1,
    borderColor: COLORS.border,
    backgroundColor: COLORS.surface,
  },
  smallBtnText: { fontFamily: FONTS.mono, fontSize: 10, fontWeight: '700', color: COLORS.textSecondary },
});
