import { useFocusEffect, useNavigation } from '@react-navigation/native';
import type { NativeStackNavigationProp } from '@react-navigation/native-stack';
import React, { useCallback } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import { Badge, Button, EmptyState, Screen, ScreenHeader } from '../components/ui';
import type { RootStackParamList } from '../navigation/types';
import { useWebhooksStore } from '../store';
import { palette, radius, spacing, typography } from '../theme/theme';

type Nav = NativeStackNavigationProp<RootStackParamList>;

const WebhookSettingsScreen: React.FC = () => {
  const nav = useNavigation<Nav>();
  const { webhooks, load } = useWebhooksStore();

  useFocusEffect(
    useCallback(() => {
      load();
    }, [load]),
  );

  return (
    <Screen>
      <ScreenHeader title="Webhooks" subtitle={`${webhooks.length} endpoint${webhooks.length === 1 ? '' : 's'}`} />

      {webhooks.length === 0 && (
        <EmptyState title="No webhooks" subtitle="Add a destination to receive forwarded SMS payloads." />
      )}

      {webhooks.map(w => (
        <Pressable key={w.id} style={styles.row} onPress={() => nav.navigate('WebhookEdit', { webhookId: w.id })}>
          <View style={{ flex: 1 }}>
            <View style={styles.titleRow}>
              <Text style={styles.name}>{w.name}</Text>
              {w.isDefault && <Badge label="DEFAULT" color={palette.accent} />}
            </View>
            <Text style={styles.url} numberOfLines={1}>
              {w.url}
            </Text>
            <View style={styles.tags}>
              {w.hasBearer && <Badge label="BEARER" color={palette.info} />}
              {w.hasHmac && <Badge label="HMAC" color={palette.warn} />}
              {!w.enabled && <Badge label="DISABLED" color={palette.textFaint} />}
            </View>
          </View>
        </Pressable>
      ))}

      <Button title="Add webhook" onPress={() => nav.navigate('WebhookEdit', {})} style={{ marginTop: spacing.sm }} />
    </Screen>
  );
};

const styles = StyleSheet.create({
  row: {
    backgroundColor: palette.surface,
    borderRadius: radius.md,
    borderWidth: 1,
    borderColor: palette.border,
    padding: spacing.md,
    marginBottom: spacing.sm,
  },
  titleRow: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm },
  name: { ...typography.heading, color: palette.text },
  url: { ...typography.body, color: palette.textMuted, marginTop: 4, fontSize: 12 },
  tags: { flexDirection: 'row', gap: spacing.sm, marginTop: spacing.sm },
});

export default WebhookSettingsScreen;
