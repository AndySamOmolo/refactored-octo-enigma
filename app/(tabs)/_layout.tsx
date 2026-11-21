import React from 'react';
import { Tabs } from 'expo-router';
import { Ionicons } from '@expo/vector-icons';
import { COLORS, FONTS } from '../../src/theme';

export default function TabLayout() {
  return (
    <Tabs
      screenOptions={{
        headerStyle: { backgroundColor: COLORS.bg },
        headerTintColor: COLORS.text,
        headerTitleStyle: { fontWeight: '700', fontSize: 18 },
        headerShadowVisible: false,
        tabBarStyle: {
          backgroundColor: COLORS.surface,
          borderTopColor: COLORS.border,
          borderTopWidth: 1,
          height: 62,
          paddingBottom: 8,
          paddingTop: 6,
        },
        tabBarActiveTintColor: COLORS.accent,
        tabBarInactiveTintColor: COLORS.textMuted,
        tabBarLabelStyle: {
          fontFamily: FONTS.mono,
          fontSize: 11,
          fontWeight: '600',
        },
      }}
    >
      <Tabs.Screen
        name="index"
        options={{
          title: 'EdgeLedger',
          tabBarLabel: 'SLATE',
          tabBarIcon: ({ color, size }) => (
            <Ionicons name="pulse-outline" size={size} color={color} />
          ),
        }}
      />
      <Tabs.Screen
        name="simulator"
        options={{
          title: 'Offline Simulator',
          tabBarLabel: 'SIM',
          tabBarIcon: ({ color, size }) => (
            <Ionicons name="calculator-outline" size={size} color={color} />
          ),
        }}
      />
      <Tabs.Screen
        name="audit"
        options={{
          title: 'Model Audit',
          tabBarLabel: 'AUDIT',
          tabBarIcon: ({ color, size }) => (
            <Ionicons name="analytics-outline" size={size} color={color} />
          ),
        }}
      />
      <Tabs.Screen
        name="ledger"
        options={{
          title: 'Paper Ledger',
          tabBarLabel: 'LEDGER',
          tabBarIcon: ({ color, size }) => (
            <Ionicons name="wallet-outline" size={size} color={color} />
          ),
        }}
      />
    </Tabs>
  );
}
