import React from 'react';
import { Pressable, StyleSheet, Switch, Text, TextInput, View } from 'react-native';
import { palette, radius, spacing, typography } from '../theme/theme';

export const TextField: React.FC<{
  label: string;
  value: string;
  onChangeText: (t: string) => void;
  placeholder?: string;
  multiline?: boolean;
  mono?: boolean;
  autoCapitalize?: 'none' | 'sentences';
  keyboardType?: 'default' | 'url' | 'numeric';
  hint?: string;
}> = ({ label, value, onChangeText, placeholder, multiline, mono, autoCapitalize = 'none', keyboardType = 'default', hint }) => (
  <View style={styles.field}>
    <Text style={styles.label}>{label.toUpperCase()}</Text>
    <TextInput
      style={[styles.input, multiline && styles.multiline, mono && typography.mono]}
      value={value}
      onChangeText={onChangeText}
      placeholder={placeholder}
      placeholderTextColor={palette.textFaint}
      multiline={multiline}
      autoCapitalize={autoCapitalize}
      autoCorrect={false}
      keyboardType={keyboardType}
    />
    {!!hint && <Text style={styles.hint}>{hint}</Text>}
  </View>
);

export const SwitchRow: React.FC<{
  label: string;
  value: boolean;
  onValueChange: (v: boolean) => void;
  description?: string;
}> = ({ label, value, onValueChange, description }) => (
  <View style={styles.switchRow}>
    <View style={{ flex: 1, paddingRight: spacing.md }}>
      <Text style={styles.switchLabel}>{label}</Text>
      {!!description && <Text style={styles.hint}>{description}</Text>}
    </View>
    <Switch
      value={value}
      onValueChange={onValueChange}
      trackColor={{ false: palette.surfaceHigh, true: palette.accentDim }}
      thumbColor={value ? palette.accent : palette.textFaint}
    />
  </View>
);

export const Segmented: React.FC<{
  label?: string;
  options: { label: string; value: string }[];
  value: string;
  onChange: (v: string) => void;
}> = ({ label, options, value, onChange }) => (
  <View style={styles.field}>
    {!!label && <Text style={styles.label}>{label.toUpperCase()}</Text>}
    <View style={styles.segment}>
      {options.map(opt => {
        const active = opt.value === value;
        return (
          <Pressable
            key={opt.value}
            onPress={() => onChange(opt.value)}
            style={[styles.segmentItem, active && styles.segmentItemActive]}>
            <Text style={[styles.segmentText, active && styles.segmentTextActive]}>{opt.label}</Text>
          </Pressable>
        );
      })}
    </View>
  </View>
);

const styles = StyleSheet.create({
  field: { marginBottom: spacing.lg },
  label: { ...typography.label, color: palette.textFaint, marginBottom: spacing.xs },
  input: {
    backgroundColor: palette.surfaceAlt,
    borderRadius: radius.md,
    borderWidth: 1,
    borderColor: palette.border,
    color: palette.text,
    paddingHorizontal: spacing.md,
    paddingVertical: 11,
    fontSize: 14,
  },
  multiline: { minHeight: 96, textAlignVertical: 'top' },
  hint: { ...typography.body, color: palette.textFaint, marginTop: 6, fontSize: 12 },
  switchRow: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingVertical: spacing.md,
  },
  switchLabel: { ...typography.body, color: palette.text, fontSize: 15 },
  segment: {
    flexDirection: 'row',
    backgroundColor: palette.surfaceAlt,
    borderRadius: radius.md,
    borderWidth: 1,
    borderColor: palette.border,
    padding: 3,
  },
  segmentItem: {
    flex: 1,
    paddingVertical: 9,
    borderRadius: radius.sm,
    alignItems: 'center',
  },
  segmentItemActive: { backgroundColor: palette.surfaceHigh },
  segmentText: { ...typography.label, color: palette.textMuted, fontSize: 11 },
  segmentTextActive: { color: palette.text },
});
