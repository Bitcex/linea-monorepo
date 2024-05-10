import { JsonRpcProvider } from "ethers";
import { describe, afterEach, jest, it, expect, beforeEach } from "@jest/globals";
import { MockProxy, mock, mockClear } from "jest-mock-extended";
import { EIP1559GasProvider } from "../EIP1559GasProvider";
import { FeeEstimationError } from "../../../core/errors/GasFeeErrors";
import { testL1NetworkConfig } from "../../../utils/testing/constants";

describe("EIP1559GasProvider", () => {
  let providerMock: MockProxy<JsonRpcProvider>;
  let eip1559GasProvider: EIP1559GasProvider;
  beforeEach(() => {
    providerMock = mock<JsonRpcProvider>();
    eip1559GasProvider = new EIP1559GasProvider(
      providerMock,
      testL1NetworkConfig.claiming.maxFeePerGas,
      testL1NetworkConfig.claiming.gasEstimationPercentile,
    );
  });

  afterEach(() => {
    mockClear(providerMock);
  });

  describe("get1559Fees", () => {
    it("should return fee from cache if currentBlockNumber == cacheIsValidForBlockNumber", async () => {
      jest.spyOn(providerMock, "getBlockNumber").mockResolvedValueOnce(0);
      const fees = await eip1559GasProvider.get1559Fees();

      expect(fees).toStrictEqual({
        maxFeePerGas: BigInt(testL1NetworkConfig.claiming.maxFeePerGas!),
      });
    });

    it("should throw an error 'FeeEstimationError' if maxPriorityFee is greater than maxFeePerGas", async () => {
      jest.spyOn(providerMock, "getBlockNumber").mockResolvedValueOnce(1);
      const sendSpy = jest.spyOn(providerMock, "send").mockResolvedValueOnce({
        baseFeePerGas: ["0x3da8e7618", "0x3e1ba3b1b", "0x3dfd72b90", "0x3d64eee76", "0x3d4da2da0", "0x3ccbcac6b"],
        gasUsedRatio: [0.5290747666666666, 0.49240453333333334, 0.4615576, 0.49407083333333335, 0.4669053],
        oldestBlock: "0xfab8ac",
        reward: [
          ["0x59682f00", "0x59682f00"],
          ["0x59682f00", "0x59682f00"],
          ["0x3b9aca00", "0x59682f00"],
          ["0x510b0870", "0x59682f00"],
          ["0x3b9aca00", "0x59682f00"],
        ],
      });
      await expect(eip1559GasProvider.get1559Fees()).rejects.toThrow(FeeEstimationError);
      expect(sendSpy).toHaveBeenCalledTimes(1);
    });

    it("should return maxFeePerGas and maxPriorityFeePerGas", async () => {
      jest.spyOn(providerMock, "getBlockNumber").mockResolvedValueOnce(1);
      const sendSpy = jest.spyOn(providerMock, "send").mockResolvedValueOnce({
        baseFeePerGas: ["0x3da8e7618", "0x3e1ba3b1b", "0x3dfd72b90", "0x3d64eee76", "0x3d4da2da0", "0x3ccbcac6b"],
        gasUsedRatio: [0.5290747666666666, 0.49240453333333334, 0.4615576, 0.49407083333333335, 0.4669053],
        oldestBlock: "0xfab8ac",
        reward: [
          ["0xe4e1c0", "0xe4e1c0"],
          ["0xe4e1c0", "0xe4e1c0"],
          ["0xe4e1c0", "0xe4e1c0"],
          ["0xcf7867", "0xe4e1c0"],
          ["0x5f5e100", "0xe4e1c0"],
        ],
      });

      const fees = await eip1559GasProvider.get1559Fees();

      expect(fees).toStrictEqual({
        maxFeePerGas: BigInt(testL1NetworkConfig.claiming.maxFeePerGas!),
        maxPriorityFeePerGas: 31_719_355n,
      });

      expect(sendSpy).toHaveBeenCalledTimes(1);
    });

    it("should return maxFeePerGas from config when maxFeePerGas and maxPriorityFeePerGas === 0", async () => {
      jest.spyOn(providerMock, "getBlockNumber").mockResolvedValueOnce(1);

      const sendSpy = jest.spyOn(providerMock, "send").mockResolvedValueOnce({
        baseFeePerGas: ["0x0", "0x0", "0x0", "0x0", "0x0", "0x0"],
        gasUsedRatio: [0, 0, 0, 0, 0],
        oldestBlock: "0xfab8ac",
        reward: [
          ["0x0", "0x0"],
          ["0x0", "0x0"],
          ["0x0", "0x0"],
          ["0x0", "0x0"],
          ["0x0", "0x0"],
        ],
      });

      const fees = await eip1559GasProvider.get1559Fees();

      expect(fees).toStrictEqual({
        maxFeePerGas: BigInt(testL1NetworkConfig.claiming.maxFeePerGas!),
      });

      expect(sendSpy).toHaveBeenCalledTimes(1);
    });
  });
});
