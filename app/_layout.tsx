import React from 'react';
import { Stack } from 'expo-router';
import { StatusBar } from 'expo-status-bar';
import { AppStateProvider } from '../src/context/AppStateContext';
import { COLORS } from '../src/theme';

export default function RootLayout() {
  return (
    <AppStateProvider>
      <StatusBar style="light" backgroundColor={COLORS.bg} />
      <Stack
        screenOptions={{
          headerStyle: { backgroundColor: COLORS.bg },
          headerTintColor: COLORS.text,
          contentStyle: { backgroundColor: COLORS.bg },
          headerShadowVisible: false,
        }}
      >
        <Stack.Screen name="(tabs)" options={{ headerShown: false }} />
        <Stack.Screen
          name="match/[id]"
          options={{
            title: 'Matchup Deep Dive',
            headerBackTitle: 'Slate',
          }}
        />
      </Stack>
    </AppStateProvider>
  );
}
