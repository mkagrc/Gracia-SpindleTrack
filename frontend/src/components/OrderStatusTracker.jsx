/**
 * OrderStatusTracker — shared horizontal stepper used by owner, staff,
 * customer, and public tracking pages.
 *
 * Props:
 *   stageHistory   array of { stage, changedAt, changedByName? }
 *   currentStage   string  e.g. "WASHING"
 *   showStaffName  boolean — when false staff names are never rendered
 *
 * The component is purely presentational: it never calls the backend.
 */

import { BadgeCheck, Clock3, Droplets, Inbox, PackageCheck, Shirt, Wind } from 'lucide-react';

export const TRACKER_STAGES = ['RECEIVED', 'WASHING', 'DRYING', 'FOLDING', 'READY', 'CLAIMED'];

const STAGE_META = {
  RECEIVED: { label: 'Pending',   Icon: Clock3       },
  WASHING:  { label: 'Washing',   Icon: Droplets     },
  DRYING:   { label: 'Drying',    Icon: Wind         },
  FOLDING:  { label: 'Folding',   Icon: Shirt        },
  READY:    { label: 'Ready',     Icon: PackageCheck },
  CLAIMED:  { label: 'Claimed',   Icon: BadgeCheck   },
};

export function stageDisplayLabel(stage) {
  const meta = STAGE_META[stage];
  if (meta) return meta.label;
  return (stage || 'UNKNOWN').replaceAll('_', ' ').toLowerCase().replace(/\b\w/g, (letter) => letter.toUpperCase());
}

/**
 * Pure helper — maps raw history + currentStage into per-step display data.
 * Exported so it can be unit-tested without rendering.
 *
 * Returns an array of:
 *   { stage, label, Icon, state: 'completed'|'current'|'future',
 *     timestamp: string|null, staffName: string|null }
 */
export function mapStageSteps(stageHistory, currentStage, showStaffName) {
  const activeIndex = TRACKER_STAGES.indexOf(currentStage);
  const historyMap = {};
  for (const event of (Array.isArray(stageHistory) ? stageHistory : [])) {
    if (event && event.stage) historyMap[event.stage] = event;
  }

  return TRACKER_STAGES.map((stage, index) => {
    const meta  = STAGE_META[stage] || { label: stageDisplayLabel(stage), Icon: Inbox };
    const event = historyMap[stage];

    let state;
    if (activeIndex < 0) {
      state = 'future';
    } else if (index < activeIndex) {
      state = 'completed';
    } else if (index === activeIndex) {
      state = 'current';
    } else {
      state = 'future';
    }

    const timestamp  = event?.changedAt ?? null;
    const staffName  = showStaffName && event?.changedByName ? event.changedByName : null;

    return { stage, label: meta.label, Icon: meta.Icon, state, timestamp, staffName };
  });
}

import { formatDateTime } from '../formatters.js';

const dateTime = (value) => {
  if (!value) return null;
  const formatted = formatDateTime(value);
  return formatted === '—' ? null : formatted;
};

export default function OrderStatusTracker({ stageHistory, currentStage, showStaffName }) {
  const steps = mapStageSteps(stageHistory, currentStage, showStaffName);

  return (
    <div className="w-full overflow-x-auto">
      <ol
        className="flex min-w-[520px] items-start gap-0"
        aria-label="Order status"
      >
        {steps.map((step, index) => {
          const { stage, label, Icon, state, timestamp, staffName } = step;
          const isLast     = index === steps.length - 1;
          const completed  = state === 'completed';
          const current    = state === 'current';
          const reached    = completed || current;

          const circleClass = reached
            ? 'bg-brand text-white ring-2 ring-brand ring-offset-2'
            : 'border-2 border-slate-200 bg-white text-slate-400';

          const labelClass = current
            ? 'font-semibold text-teal-800'
            : reached
              ? 'text-slate-700'
              : 'text-slate-400';

          const ariaLabel = `${label}, ${state}`;

          return (
            <li
              key={stage}
              className="flex flex-1 flex-col items-center"
              aria-label={ariaLabel}
              aria-current={current ? 'step' : undefined}
            >
              {/* Row: circle + connector */}
              <div className="flex w-full items-center">
                {/* Circle */}
                <div className="flex flex-col items-center">
                  <span
                    className={`grid h-9 w-9 place-items-center rounded-full ${circleClass} transition-colors`}
                    aria-hidden="true"
                  >
                    <Icon size={18} />
                  </span>
                </div>

                {/* Connector line to next step */}
                {!isLast && (
                  <span
                    className={`h-0.5 flex-1 transition-colors ${completed ? 'bg-brand' : 'bg-slate-200'}`}
                    aria-hidden="true"
                  />
                )}
              </div>

              {/* Label + timestamp under the circle */}
              <div className="mt-2 w-full max-w-[80px] text-center">
                <p className={`text-[11px] leading-tight ${labelClass}`}>{label}</p>
                {timestamp && (
                  <p className="mt-0.5 text-[10px] leading-tight text-slate-500">
                    {dateTime(timestamp)}
                  </p>
                )}
                {staffName && (
                  <p className="mt-0.5 text-[10px] leading-tight text-slate-400">{staffName}</p>
                )}
              </div>
            </li>
          );
        })}
      </ol>
    </div>
  );
}
