"use client";

import Image from "next/image";
import { NetworkType } from "@/config";
import { useChainStore } from "@/stores/chainStore";
import { useEffect, useRef } from "react";
import DropdownItem from "@/components/DropdownItem";
import { getChainLogoPath } from "@/utils/chainsUtil";
import { useFormContext } from "react-hook-form";

export default function FromChainDropdown() {
  const { networkType, fromChain, toChain, switchChain } = useChainStore((state) => ({
    networkType: state.networkType,
    fromChain: state.fromChain,
    toChain: state.toChain,
    switchChain: state.switchChain,
  }));
  const { reset } = useFormContext();

  const detailsRef = useRef<HTMLDetailsElement>(null);

  useEffect(() => {
    const handleClickOutside = (e: MouseEvent) => {
      if (detailsRef.current && !detailsRef.current.contains(e.target as Node)) {
        detailsRef.current.removeAttribute("open");
      }
    };
    document.addEventListener("click", handleClickOutside);
    return () => {
      document.removeEventListener("click", handleClickOutside);
    };
  }, []);

  const switchNetworkHandler = async () => {
    switchChain();
    reset();
  };

  if (networkType == NetworkType.SEPOLIA || networkType == NetworkType.MAINNET) {
    return (
      <details className="dropdown relative" ref={detailsRef}>
        <summary className="flex cursor-pointer items-center gap-2 rounded-full bg-[#2D2D2D] p-2 px-3 text-white">
          {fromChain && (
            <Image
              src={getChainLogoPath(fromChain.id)}
              alt="MetaMask"
              width={0}
              height={0}
              style={{ width: "18px", height: "auto" }}
            />
          )}
          <span className="hidden md:block">{fromChain?.name}</span>
          <svg
            className="size-4 text-card transition-transform"
            fill="none"
            stroke={"white"}
            viewBox="0 0 24 24"
            xmlns="http://www.w3.org/2000/svg"
          >
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="3" d="M19 9l-7 7-7-7"></path>
          </svg>
        </summary>
        <ul className="menu dropdown-content absolute right-0 z-10 mt-2 min-w-max border-2 border-card bg-cardBg p-0 shadow">
          <DropdownItem
            title={toChain?.name || ""}
            iconPath={toChain && getChainLogoPath(toChain.id)}
            onClick={switchNetworkHandler}
          />
        </ul>
      </details>
    );
  }
}