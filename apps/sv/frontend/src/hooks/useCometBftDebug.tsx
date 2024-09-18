// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { useQuery, UseQueryResult } from '@tanstack/react-query';
import { PollingStrategy } from 'common-frontend-utils';
import { CometBftNodeDumpOrErrorResponse } from 'sv-openapi';

import { useSvAdminClient } from '../contexts/SvAdminServiceContext';

export const useCometBftDebug = (): UseQueryResult<CometBftNodeDumpOrErrorResponse> => {
  const { getCometBftNodeDebug } = useSvAdminClient();
  return useQuery({
    refetchInterval: PollingStrategy.FIXED,
    queryKey: ['getCometBftNodeDebug'],
    queryFn: async () => {
      const cometBftNodeDebug = await getCometBftNodeDebug();
      return cometBftNodeDebug;
    },
  });
};
